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


package com.dtstack.flink.sql.side.oracle;

import com.dtstack.flink.sql.side.FieldInfo;
import com.dtstack.flink.sql.side.JoinInfo;
import com.dtstack.flink.sql.side.SideTableInfo;
import com.dtstack.flink.sql.side.rdb.async.RdbAsyncReqRow;
import com.dtstack.flink.sql.side.rdb.table.RdbSideTableInfo;
import com.dtstack.flink.sql.util.JDBCUtils;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;


public class OracleAsyncReqRow extends RdbAsyncReqRow {

    private static final Logger LOG = LoggerFactory.getLogger(OracleAsyncReqRow.class);

    private static final String ORACLE_DRIVER = "oracle.jdbc.driver.OracleDriver";

    public OracleAsyncReqRow(RowTypeInfo rowTypeInfo, JoinInfo joinInfo, List<FieldInfo> outFieldInfoList, SideTableInfo sideTableInfo) {
        super(new OracleAsyncSideInfo(rowTypeInfo, joinInfo, outFieldInfoList, sideTableInfo));
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        JsonObject oracleClientConfig = new JsonObject();
        RdbSideTableInfo rdbSideTableInfo = (RdbSideTableInfo) sideInfo.getSideTableInfo();
        establishConnection(rdbSideTableInfo);
        oracleClientConfig.put("url", rdbSideTableInfo.getUrl())
                .put("driver_class", ORACLE_DRIVER)
                .put("max_pool_size", rdbPoolSize)
                .put("user", rdbSideTableInfo.getUserName())
                .put("password", rdbSideTableInfo.getPassword())
                .put("provider_class", DT_PROVIDER_CLASS)
                .put("preferred_test_query", PREFERRED_TEST_QUERY_SQL)
                .put("idle_connection_test_period", DEFAULT_IDLE_CONNECTION_TEST_PEROID)
                .put("test_connection_on_checkin", DEFAULT_TEST_CONNECTION_ON_CHECKIN);

        System.setProperty("vertx.disableFileCPResolving", "true");

        VertxOptions vo = new VertxOptions();
        vo.setEventLoopPoolSize(DEFAULT_VERTX_EVENT_LOOP_POOL_SIZE);
        vo.setWorkerPoolSize(rdbPoolSize);
        vo.setFileResolverCachingEnabled(false);
        Vertx vertx = Vertx.vertx(vo);
        setRdbSQLClient(JDBCClient.createNonShared(vertx, oracleClientConfig));
    }

    private void establishConnection(RdbSideTableInfo rdbSideTableInfo) {
        Connection connection = null;
        JDBCUtils.forName(ORACLE_DRIVER, getClass().getClassLoader());
        try {
            if (rdbSideTableInfo.getUserName() == null) {
                connection = DriverManager.getConnection(rdbSideTableInfo.getUrl());
            } else {
                connection = DriverManager.getConnection(rdbSideTableInfo.getUrl(), rdbSideTableInfo.getUserName(), rdbSideTableInfo.getPassword());
            }
            if (null != connection) {
                if (!connection.getMetaData().getTables(null, rdbSideTableInfo.getSchema(), rdbSideTableInfo.getTableName(), null).next()) {
                    LOG.error("Table " + rdbSideTableInfo.getTableName() + " doesn't exist");
                    throw new RuntimeException("Table " + rdbSideTableInfo.getTableName() + " doesn't exist");
                }
            }
        } catch (SQLException sqe) {
            LOG.error("", sqe);
            throw new IllegalArgumentException("open() failed.", sqe);
        } finally {
            if (null != connection) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    LOG.error("", e);
                    throw new IllegalArgumentException("close() failed.", e);
                }
            }
        }
    }
}
