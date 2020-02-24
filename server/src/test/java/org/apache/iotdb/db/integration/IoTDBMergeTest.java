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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.jdbc.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IoTDBMergeTest {
  private static final Logger logger = LoggerFactory.getLogger(IoTDBMergeTest.class);
  private long prevPartitionInterval;
  @Before
  public void setUp() throws Exception {
    EnvironmentUtils.closeStatMonitor();

    EnvironmentUtils.envSetUp();
    prevPartitionInterval = IoTDBDescriptor.getInstance().getConfig().getPartitionInterval();
    IoTDBDescriptor.getInstance().getConfig().setPartitionInterval(1);
    Class.forName(Config.JDBC_DRIVER_NAME);
  }

  @After
  public void tearDown() throws Exception {
    EnvironmentUtils.cleanEnv();
    IoTDBDescriptor.getInstance().getConfig().setPartitionInterval(prevPartitionInterval);
  }

  @Test
  public void test() throws SQLException {
    logger.info("test...");
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      statement.execute("SET STORAGE GROUP TO root.mergeTest");
      for (int i = 1; i <= 3; i++) {
        try {
          statement.execute("CREATE TIMESERIES root.mergeTest.d0.s" + i + " WITH DATATYPE=INT64,"
              + "ENCODING=PLAIN");
        } catch (SQLException e) {
          // ignore
        }
      }

      for (int i = 0; i < 10; i++) {
        logger.info("Running the {} round merge", i);
        for (int j = i * 10 + 1; j <= (i+1) * 10; j++) {
          statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
              + "%d,%d)", j, j+1, j+2, j+3));
        }
        statement.execute("FLUSH");
        for (int j = i * 10 + 1; j <= (i+1) * 10; j++) {
          statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
              + "%d,%d)", j, j+10, j+20, j+30));
        }
        statement.execute("FLUSH");
        statement.execute("MERGE");

        int cnt;
        try (ResultSet resultSet = statement.executeQuery("SELECT * FROM root.mergeTest")) {
          cnt = 0;
          while (resultSet.next()) {
            long time = resultSet.getLong("Time");
            long s1 = resultSet.getLong("root.mergeTest.d0.s1");
            long s2 = resultSet.getLong("root.mergeTest.d0.s2");
            long s3 = resultSet.getLong("root.mergeTest.d0.s3");
            assertEquals(time + 10, s1);
            assertEquals(time + 20, s2);
            assertEquals(time + 30, s3);
            cnt++;
          }
        }
        assertEquals((i + 1) * 10, cnt);
      }
    }
  }

  @Test
  public void testInvertedOrder() {
    logger.info("testInvertedOrder...");
    // case: seq data and unseq data are written in reverted order
    // e.g.: write 1. seq [10, 20), 2. seq [20, 30), 3. unseq [20, 30), 4. unseq [10, 20)
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      statement.execute("SET STORAGE GROUP TO root.mergeTest");
      for (int i = 1; i <= 3; i++) {
        try {
          statement.execute("CREATE TIMESERIES root.mergeTest.d0.s" + i + " WITH DATATYPE=INT64,"
              + "ENCODING=PLAIN");
        } catch (SQLException e) {
          // ignore
        }
      }

      for (int j = 10; j < 20; j++) {
        statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
            + "%d,%d)", j, j+1, j+2, j+3));
      }
      statement.execute("FLUSH");
      for (int j = 20; j < 30; j++) {
        statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
            + "%d,%d)", j, j+1, j+2, j+3));
      }
      statement.execute("FLUSH");

      for (int j = 20; j < 30; j++) {
        statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
            + "%d,%d)", j, j+10, j+20, j+30));
      }
      statement.execute("FLUSH");
      for (int j = 10; j < 20; j++) {
        statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
            + "%d,%d)", j, j+10, j+20, j+30));
      }
      statement.execute("FLUSH");

      statement.execute("MERGE");

      int cnt;
      try (ResultSet resultSet = statement.executeQuery("SELECT * FROM root.mergeTest")) {
        cnt = 0;
        while (resultSet.next()) {
          long time = resultSet.getLong("Time");
          long s1 = resultSet.getLong("root.mergeTest.d0.s1");
          long s2 = resultSet.getLong("root.mergeTest.d0.s2");
          long s3 = resultSet.getLong("root.mergeTest.d0.s3");
          assertEquals(cnt + 10, time);
          assertEquals(time + 10, s1);
          assertEquals(time + 20, s2);
          assertEquals(time + 30, s3);
          cnt++;
        }
      }
      assertEquals(20, cnt);
    } catch (SQLException e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testCrossPartition() throws SQLException, StorageEngineException {
    logger.info("testCrossPartition...");
    try (Connection connection = DriverManager
        .getConnection(Config.IOTDB_URL_PREFIX + "127.0.0.1:6667/", "root", "root");
        Statement statement = connection.createStatement()) {
      statement.execute("SET STORAGE GROUP TO root.mergeTest");
      for (int i = 1; i <= 3; i++) {
        try {
          statement.execute("CREATE TIMESERIES root.mergeTest.d0.s" + i + " WITH DATATYPE=INT64,"
              + "ENCODING=PLAIN");
        } catch (SQLException e) {
          // ignore
        }
      }

      // file in partition
      for (int k = 0; k < 7; k++) {
        // partition num
        for (int i = 0; i < 10; i++) {
          // sequence files
          for (int j = i * 1000 + 300 + k * 100; j <= i * 1000 + 399 + k * 100; j++) {
            statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
                + "%d,%d)", j, j+1, j+2, j+3));
          }
          statement.execute("FLUSH");
          // unsequence files
          for (int j = i * 1000 + k * 100; j <= i * 1000 + 99 + k * 100; j++) {
            statement.execute(String.format("INSERT INTO root.mergeTest.d0(timestamp,s1,s2,s3) VALUES (%d,%d,"
                + "%d,%d)", j, j+10, j+20, j+30));
          }
          statement.execute("FLUSH");
        }
      }

      statement.execute("MERGE");

      int cnt;
      try (ResultSet resultSet = statement.executeQuery("SELECT * FROM root.mergeTest")) {
        cnt = 0;
        while (resultSet.next()) {
          long time = resultSet.getLong("Time");
          long s1 = resultSet.getLong("root.mergeTest.d0.s1");
          long s2 = resultSet.getLong("root.mergeTest.d0.s2");
          long s3 = resultSet.getLong("root.mergeTest.d0.s3");
          assertEquals(cnt, time);
          if (time % 1000 < 700) {
            assertEquals(time + 10, s1);
            assertEquals(time + 20, s2);
            assertEquals(time + 30, s3);
          } else {
            assertEquals(time + 1, s1);
            assertEquals(time + 2, s2);
            assertEquals(time + 3, s3);
          }
          cnt++;
        }
      }
      assertEquals(10000, cnt);
    }
  }
}
