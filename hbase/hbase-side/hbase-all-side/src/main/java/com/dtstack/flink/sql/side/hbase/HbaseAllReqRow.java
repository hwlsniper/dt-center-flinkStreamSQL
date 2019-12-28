/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package com.dtstack.flink.sql.side.hbase;

import com.dtstack.flink.sql.side.*;
import com.dtstack.flink.sql.side.hbase.table.HbaseSideTableInfo;
import com.dtstack.flink.sql.side.hbase.utils.HbaseConfigUtils;
import org.apache.calcite.sql.JoinType;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import com.google.common.collect.Maps;
import org.apache.flink.table.typeutils.TimeIndicatorTypeInfo;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivilegedAction;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class HbaseAllReqRow extends AllReqRow {

    private static final Logger LOG = LoggerFactory.getLogger(HbaseAllReqRow.class);

    private String tableName;

    private Map<String, String> aliasNameInversion;

    private AtomicReference<Map<String, Map<String, Object>>> cacheRef = new AtomicReference<>();

    private Connection conn = null;
    private Table table = null;
    private ResultScanner resultScanner = null;
    private Configuration conf = null;

    public HbaseAllReqRow(RowTypeInfo rowTypeInfo, JoinInfo joinInfo, List<FieldInfo> outFieldInfoList, SideTableInfo sideTableInfo) {
        super(new HbaseAllSideInfo(rowTypeInfo, joinInfo, outFieldInfoList, sideTableInfo));
        tableName = ((HbaseSideTableInfo)sideTableInfo).getTableName();

        HbaseSideTableInfo hbaseSideTableInfo = (HbaseSideTableInfo) sideTableInfo;
        Map<String, String> aliasNameRef = hbaseSideTableInfo.getAliasNameRef();
        aliasNameInversion = new HashMap<>();
        for(Map.Entry<String, String> entry : aliasNameRef.entrySet()){
            aliasNameInversion.put(entry.getValue(), entry.getKey());
        }
    }

    @Override
    public Row fillData(Row input, Object sideInput) {
        Map<String, Object> sideInputList = (Map<String, Object>) sideInput;
        Row row = new Row(sideInfo.getOutFieldInfoList().size());
        for(Map.Entry<Integer, Integer> entry : sideInfo.getInFieldIndex().entrySet()){
            Object obj = input.getField(entry.getValue());
            boolean isTimeIndicatorTypeInfo = TimeIndicatorTypeInfo.class.isAssignableFrom(sideInfo.getRowTypeInfo().getTypeAt(entry.getValue()).getClass());

            //Type information for indicating event or processing time. However, it behaves like a regular SQL timestamp but is serialized as Long.
            if (obj instanceof LocalDateTime && isTimeIndicatorTypeInfo) {
                obj = Timestamp.valueOf(((LocalDateTime) obj));
            }
            row.setField(entry.getKey(), obj);
        }

        for(Map.Entry<Integer, Integer> entry : sideInfo.getSideFieldIndex().entrySet()){
            if(sideInputList == null){
                row.setField(entry.getKey(), null);
            }else{
                String key = sideInfo.getSideFieldNameIndex().get(entry.getKey());
                row.setField(entry.getKey(), sideInputList.get(key));
            }
        }

        return row;
    }

    @Override
    protected void initCache() throws SQLException {
        Map<String, Map<String, Object>> newCache = Maps.newConcurrentMap();
        cacheRef.set(newCache);
        loadData(newCache);
    }

    @Override
    protected void reloadCache() {
        Map<String, Map<String, Object>> newCache = Maps.newConcurrentMap();
        try {
            loadData(newCache);
        } catch (SQLException e) {
            LOG.error("", e);
        }

        cacheRef.set(newCache);
        LOG.info("----- HBase all cacheRef reload end:{}", Calendar.getInstance());
    }

    @Override
    public void flatMap(Row value, Collector<Row> out) throws Exception {
        Map<String, Object> refData = Maps.newHashMap();
        for (int i = 0; i < sideInfo.getEqualValIndex().size(); i++) {
            Integer conValIndex = sideInfo.getEqualValIndex().get(i);
            Object equalObj = value.getField(conValIndex);
            if(equalObj == null){
                if(sideInfo.getJoinType() == JoinType.LEFT){
                    Row data = fillData(value, null);
                    out.collect(data);
                }
                return;
            }
            refData.put(sideInfo.getEqualFieldList().get(i), equalObj);
        }

        String rowKeyStr = ((HbaseAllSideInfo)sideInfo).getRowKeyBuilder().getRowKey(refData);

        Map<String, Object> cacheList = null;

        SideTableInfo sideTableInfo = sideInfo.getSideTableInfo();
        HbaseSideTableInfo hbaseSideTableInfo = (HbaseSideTableInfo) sideTableInfo;
        if (hbaseSideTableInfo.isPreRowKey())
        {
            for (Map.Entry<String, Map<String, Object>> entry : cacheRef.get().entrySet()){
                if (entry.getKey().startsWith(rowKeyStr))
                {
                    cacheList = cacheRef.get().get(entry.getKey());
                    Row row = fillData(value, cacheList);
                    out.collect(row);
                }
            }
        } else {
            cacheList = cacheRef.get().get(rowKeyStr);
            Row row = fillData(value, cacheList);
            out.collect(row);
        }

    }

    private void loadData(Map<String, Map<String, Object>> tmpCache) throws SQLException {
        SideTableInfo sideTableInfo = sideInfo.getSideTableInfo();
        HbaseSideTableInfo hbaseSideTableInfo = (HbaseSideTableInfo) sideTableInfo;
        boolean openKerberos = hbaseSideTableInfo.isKerberosAuthEnable();
        try {
            conf = HBaseConfiguration.create();
            if (openKerberos) {
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_QUORUM, hbaseSideTableInfo.getHost());
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_ZNODE_QUORUM, hbaseSideTableInfo.getParent());

                fillSyncKerberosConfig(conf,hbaseSideTableInfo);
                LOG.info("hbase.security.authentication:{}", conf.get("hbase.security.authentication"));
                LOG.info("hbase.security.authorization:{}", conf.get("hbase.security.authorization"));
                LOG.info("hbase.master.keytab.file:{}", conf.get("hbase.master.keytab.file"));
                LOG.info("hbase.master.kerberos.principal:{}", conf.get("hbase.master.kerberos.principal"));
                LOG.info("hbase.regionserver.keytab.file:{}", conf.get("hbase.regionserver.keytab.file"));
                LOG.info("hbase.regionserver.kerberos.principal:{}", conf.get("hbase.regionserver.kerberos.principal"));

                UserGroupInformation userGroupInformation = HbaseConfigUtils.loginAndReturnUGI(conf, hbaseSideTableInfo.getRegionserverPrincipal(),
                        hbaseSideTableInfo.getRegionserverKeytabFile());

                Configuration finalConf = conf;
                conn = userGroupInformation.doAs(new PrivilegedAction<Connection>() {
                    @Override
                    public Connection run() {
                        try {
                            return ConnectionFactory.createConnection(finalConf);
                        } catch (IOException e) {
                            LOG.error("Get connection fail with config:{}", finalConf);
                            throw new RuntimeException(e);
                        }
                    }
                });

            } else {
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_QUORUM, hbaseSideTableInfo.getHost());
                conf.set(HbaseConfigUtils.KEY_HBASE_ZOOKEEPER_ZNODE_QUORUM, hbaseSideTableInfo.getParent());
                conn = ConnectionFactory.createConnection(conf);
            }

            table = conn.getTable(TableName.valueOf(tableName));
            resultScanner = table.getScanner(new Scan());
            for (Result r : resultScanner) {
                Map<String, Object> kv = new HashedMap();
                for (Cell cell : r.listCells())
                {
                    String family = Bytes.toString(CellUtil.cloneFamily(cell));
                    String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
                    String value = Bytes.toString(CellUtil.cloneValue(cell));
                    StringBuilder key = new StringBuilder();
                    key.append(family).append(":").append(qualifier);

                    kv.put(aliasNameInversion.get(key.toString()), value);
                }
                tmpCache.put(new String(r.getRow()), kv);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (null != conn) {
                    conn.close();
                }

                if (null != table) {
                    table.close();
                }

                if (null != resultScanner) {
                    resultScanner.close();
                }
            } catch (IOException e) {
                LOG.error("", e);
            }
        }
    }

    private void fillSyncKerberosConfig(Configuration config, HbaseSideTableInfo hbaseSideTableInfo) throws IOException {
        String regionserverKeytabFile = hbaseSideTableInfo.getRegionserverKeytabFile();
        if (StringUtils.isEmpty(regionserverKeytabFile)) {
            throw new IllegalArgumentException("Must provide regionserverKeytabFile when authentication is Kerberos");
        }
        String regionserverKeytabFilePath = HbaseConfigUtils.getAbsolutebPath(regionserverKeytabFile);
        LOG.info("regionserverKeytabFilePath:{}", regionserverKeytabFilePath);
        config.set(HbaseConfigUtils.KEY_HBASE_MASTER_KEYTAB_FILE, regionserverKeytabFilePath);
        config.set(HbaseConfigUtils.KEY_HBASE_REGIONSERVER_KEYTAB_FILE, regionserverKeytabFilePath);

        String regionserverPrincipal = hbaseSideTableInfo.getRegionserverPrincipal();
        if (StringUtils.isEmpty(regionserverPrincipal)) {
            throw new IllegalArgumentException("Must provide regionserverPrincipal when authentication is Kerberos");
        }
        config.set(HbaseConfigUtils.KEY_HBASE_MASTER_KERBEROS_PRINCIPAL, regionserverPrincipal);
        config.set(HbaseConfigUtils.KEY_HBASE_REGIONSERVER_KERBEROS_PRINCIPAL, regionserverPrincipal);
        config.set(HbaseConfigUtils.KEY_HBASE_SECURITY_AUTHORIZATION, "true");
        config.set(HbaseConfigUtils.KEY_HBASE_SECURITY_AUTHENTICATION, "kerberos");



        if (!StringUtils.isEmpty(hbaseSideTableInfo.getZookeeperSaslClient())) {
            System.setProperty(HbaseConfigUtils.KEY_ZOOKEEPER_SASL_CLIENT, hbaseSideTableInfo.getZookeeperSaslClient());
        }

        if (!StringUtils.isEmpty(hbaseSideTableInfo.getSecurityKrb5Conf())) {
            String krb5ConfPath = HbaseConfigUtils.getAbsolutebPath(hbaseSideTableInfo.getSecurityKrb5Conf());
            LOG.info("krb5ConfPath:{}", krb5ConfPath);
            System.setProperty(HbaseConfigUtils.KEY_JAVA_SECURITY_KRB5_CONF, HbaseConfigUtils.getAbsolutebPath(krb5ConfPath));
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (null != conn) {
                conn.close();
            }

            if (null != table) {
                table.close();
            }

            if (null != resultScanner) {
                resultScanner.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}