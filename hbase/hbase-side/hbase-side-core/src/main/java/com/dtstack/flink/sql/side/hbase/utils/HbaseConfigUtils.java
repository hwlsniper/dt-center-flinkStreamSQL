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

package com.dtstack.flink.sql.side.hbase.utils;

import com.dtstack.flink.sql.side.hbase.table.HbaseSideTableInfo;
import com.dtstack.flink.sql.util.AuthUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 *
 *  The utility class of HBase connection
 *
 * Date: 2019/12/24
 * Company: www.dtstack.com
 * @author maqi
 */
public class HbaseConfigUtils {

    private static final Logger LOG = LoggerFactory.getLogger(HbaseConfigUtils.class);
    // sync side kerberos
    public final static String KEY_HBASE_SECURITY_AUTHENTICATION = "hbase.security.authentication";
    public final static String KEY_HBASE_SECURITY_AUTHORIZATION = "hbase.security.authorization";
    public final static String KEY_HBASE_MASTER_KEYTAB_FILE = "hbase.master.keytab.file";
    public final static String KEY_HBASE_MASTER_KERBEROS_PRINCIPAL = "hbase.master.kerberos.principal";
    public final static String KEY_HBASE_REGIONSERVER_KEYTAB_FILE = "hbase.regionserver.keytab.file";
    public final static String KEY_HBASE_REGIONSERVER_KERBEROS_PRINCIPAL = "hbase.regionserver.kerberos.principal";

    // async side kerberos
    public final static String KEY_HBASE_SECURITY_AUTH_ENABLE = "hbase.security.auth.enable";
    public final static String KEY_HBASE_SASL_CLIENTCONFIG = "hbase.sasl.clientconfig";
    public final static String KEY_HBASE_KERBEROS_REGIONSERVER_PRINCIPAL = "hbase.kerberos.regionserver.principal";

    public final static String KEY_HBASE_ZOOKEEPER_QUORUM = "hbase.zookeeper.quorum";
    public final static String KEY_HBASE_ZOOKEEPER_ZNODE_QUORUM = "hbase.zookeeper.znode.parent";


    public static final String KEY_JAVA_SECURITY_KRB5_CONF = "java.security.krb5.conf";
    public static final String KEY_ZOOKEEPER_SASL_CLIENT = "zookeeper.sasl.client";

    public static final String KEY_JAVA_SECURITY_AUTH_LOGIN_CONF = "java.security.auth.login.config";


    public static AuthUtil.JAASConfig buildJaasConfig(HbaseSideTableInfo hbaseSideTableInfo) {
        String keytabPath = System.getProperty("user.dir") + File.separator + hbaseSideTableInfo.getRegionserverKeytabFile();
        Map<String, String> loginModuleOptions = new HashMap<>();
        loginModuleOptions.put("useKeyTab", "true");
        loginModuleOptions.put("useTicketCache", "false");
        loginModuleOptions.put("keyTab", "\"" + keytabPath + "\"");
        loginModuleOptions.put("principal", "\"" + hbaseSideTableInfo.getJaasPrincipal() + "\"");
        return AuthUtil.JAASConfig.builder().setEntryName("Client")
                .setLoginModule("com.sun.security.auth.module.Krb5LoginModule")
                .setLoginModuleFlag("required").setLoginModuleOptions(loginModuleOptions).build();
    }


    public static UserGroupInformation loginAndReturnUGI(Configuration conf, String principal, String keytab) throws IOException {
        if (conf == null) {
            throw new IllegalArgumentException("kerberos conf can not be null");
        }

        if (org.apache.commons.lang.StringUtils.isEmpty(principal)) {
            throw new IllegalArgumentException("principal can not be null");
        }

        if (org.apache.commons.lang.StringUtils.isEmpty(keytab)) {
            throw new IllegalArgumentException("keytab can not be null");
        }

        conf.set("hadoop.security.authentication", "Kerberos");
        UserGroupInformation.setConfiguration(conf);

        return UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytab);
    }

}
