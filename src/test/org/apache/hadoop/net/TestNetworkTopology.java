/**
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

package org.apache.hadoop.net;


import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.DatanodeDescriptor;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants.DatanodeReportType;
import org.apache.hadoop.net.NetworkTopology.InvalidTopologyException;
import org.junit.Assert;

public class TestNetworkTopology extends TestCase {
  private final static NetworkTopology cluster = new NetworkTopology();
  private final static DatanodeDescriptor dataNodes[] = new DatanodeDescriptor[] {
    new DatanodeDescriptor(new DatanodeID("h1:5020"), "/d1/r1"),
    new DatanodeDescriptor(new DatanodeID("h2:5020"), "/d1/r1"),
    new DatanodeDescriptor(new DatanodeID("h3:5020"), "/d1/r2"),
    new DatanodeDescriptor(new DatanodeID("h4:5020"), "/d1/r2"),
    new DatanodeDescriptor(new DatanodeID("h5:5020"), "/d1/r2"),
    new DatanodeDescriptor(new DatanodeID("h6:5020"), "/d2/r3"),
    new DatanodeDescriptor(new DatanodeID("h7:5020"), "/d2/r3")
  };
  private final static DatanodeDescriptor NODE = 
    new DatanodeDescriptor(new DatanodeID("h8:5020"), "/d2/r4");
  
  private static final Log LOG = LogFactory.getLog(TestNetworkTopology.class);
  
  static {
    for(int i=0; i<dataNodes.length; i++) {
      cluster.add(dataNodes[i]);
    }
  }
  
  public void testContains() throws Exception {
    for(int i=0; i<dataNodes.length; i++) {
      assertTrue(cluster.contains(dataNodes[i]));
    }
    assertFalse(cluster.contains(NODE));
  }
  
  public void testNumOfChildren() throws Exception {
    assertEquals(cluster.getNumOfLeaves(), dataNodes.length);
  }

  public void testCreateInvalidTopology() throws Exception {
    NetworkTopology invalCluster = new NetworkTopology();
    DatanodeDescriptor invalDataNodes[] = new DatanodeDescriptor[] {
      new DatanodeDescriptor(new DatanodeID("h1:5020"), "/d1/r1"),
      new DatanodeDescriptor(new DatanodeID("h2:5020"), "/d1/r1"),
      new DatanodeDescriptor(new DatanodeID("h3:5020"), "/d1")
    };
    invalCluster.add(invalDataNodes[0]);
    invalCluster.add(invalDataNodes[1]);
    try {
      invalCluster.add(invalDataNodes[2]);
      fail("expected InvalidTopologyException");
    } catch (InvalidTopologyException e) {
      assertEquals(e.getMessage(), "Invalid network topology. " +
          "You cannot have a rack and a non-rack node at the same " +
          "level of the network topology.");
    }
  }

  public void testRacks() throws Exception {
    assertEquals(cluster.getNumOfRacks(), 3);
    assertTrue(cluster.isOnSameRack(dataNodes[0], dataNodes[1]));
    assertFalse(cluster.isOnSameRack(dataNodes[1], dataNodes[2]));
    assertTrue(cluster.isOnSameRack(dataNodes[2], dataNodes[3]));
    assertTrue(cluster.isOnSameRack(dataNodes[3], dataNodes[4]));
    assertFalse(cluster.isOnSameRack(dataNodes[4], dataNodes[5]));
    assertTrue(cluster.isOnSameRack(dataNodes[5], dataNodes[6]));
  }
  
  public void testGetDistance() throws Exception {
    assertEquals(cluster.getDistance(dataNodes[0], dataNodes[0]), 0);
    assertEquals(cluster.getDistance(dataNodes[0], dataNodes[1]), 2);
    assertEquals(cluster.getDistance(dataNodes[0], dataNodes[3]), 4);
    assertEquals(cluster.getDistance(dataNodes[0], dataNodes[6]), 6);
  }

  public void testPseudoSortByDistance() throws Exception {
    DatanodeDescriptor[] testNodes = new DatanodeDescriptor[3];
    
    // array contains both local node & local rack node
    testNodes[0] = dataNodes[1];
    testNodes[1] = dataNodes[2];
    testNodes[2] = dataNodes[0];
    cluster.pseudoSortByDistance(dataNodes[0], testNodes );
    assertTrue(testNodes[0] == dataNodes[0]);
    assertTrue(testNodes[1] == dataNodes[1]);
    assertTrue(testNodes[2] == dataNodes[2]);

    // array contains local node
    testNodes[0] = dataNodes[1];
    testNodes[1] = dataNodes[3];
    testNodes[2] = dataNodes[0];
    cluster.pseudoSortByDistance(dataNodes[0], testNodes );
    assertTrue(testNodes[0] == dataNodes[0]);
    assertTrue(testNodes[1] == dataNodes[1]);
    assertTrue(testNodes[2] == dataNodes[3]);

    // array contains local rack node
    testNodes[0] = dataNodes[5];
    testNodes[1] = dataNodes[3];
    testNodes[2] = dataNodes[1];
    cluster.pseudoSortByDistance(dataNodes[0], testNodes );
    assertTrue(testNodes[0] == dataNodes[1]);
    assertTrue(testNodes[1] == dataNodes[3]);
    assertTrue(testNodes[2] == dataNodes[5]);
  }
  
  public void testRemove() throws Exception {
    for(int i=0; i<dataNodes.length; i++) {
      cluster.remove(dataNodes[i]);
    }
    for(int i=0; i<dataNodes.length; i++) {
      assertFalse(cluster.contains(dataNodes[i]));
    }
    assertEquals(0, cluster.getNumOfLeaves());
    for(int i=0; i<dataNodes.length; i++) {
      cluster.add(dataNodes[i]);
    }
  }
  
  /**
   * This picks a large number of nodes at random in order to ensure coverage
   * 
   * @param numNodes the number of nodes
   * @param excludedScope the excluded scope
   * @return the frequency that nodes were chosen
   */
  private Map<Node, Integer> pickNodesAtRandom(int numNodes,
      String excludedScope) {
    Map<Node, Integer> frequency = new HashMap<Node, Integer>();
    for (DatanodeDescriptor dnd : dataNodes) {
      frequency.put(dnd, 0);
    }

    for (int j = 0; j < numNodes; j++) {
      Node random = cluster.chooseRandom(excludedScope);
      frequency.put(random, frequency.get(random) + 1);
    }
    return frequency;
  }

  /**
   * This test checks that chooseRandom works for an excluded node.
   */
  public void testChooseRandomExcludedNode() {
    String scope = "~" + NodeBase.getPath(dataNodes[0]);
    Map<Node, Integer> frequency = pickNodesAtRandom(100, scope);

    for (Node key : dataNodes) {
      // all nodes except the first should be more than zero
      assertTrue(frequency.get(key) > 0 || key == dataNodes[0]);
    }
  }

  /**
   * This test checks that chooseRandom works for an excluded rack.
   */
  public void testChooseRandomExcludedRack() {
    Map<Node, Integer> frequency = pickNodesAtRandom(100, "~" + "/d2");
    // all the nodes on the second rack should be zero
    for (int j = 0; j < dataNodes.length; j++) {
      int freq = frequency.get(dataNodes[j]);
      if (dataNodes[j].getNetworkLocation().startsWith("/d2")) {
        assertEquals(0, freq);
      } else {
        assertTrue(freq > 0);
      }
    }
  }
  
  public void testInvalidNetworkTopologiesNotCachedInHdfs() throws Exception {
    // start a cluster
    Configuration conf = new Configuration();
    MiniDFSCluster cluster = null;
    try {
      // bad rack topology
      String racks[] = { "/a/b", "/c" };
      String hosts[] = { "foo1.example.com", "foo2.example.com" };

      cluster = new MiniDFSCluster(conf, 2, true, racks, hosts);
      
      cluster.waitActive();
     
      ClientProtocol client = DFSClient.createNamenode(conf);
      
      Assert.assertNotNull(client);
      
      // Wait for one DataNode to register.
      // The other DataNode will not be able to register up because of the rack mismatch.
      DatanodeInfo[] info;
      while (true) {
        info = client.getDatanodeReport(DatanodeReportType.LIVE);
        Assert.assertFalse(info.length == 2);
        if (info.length == 1) {
          break;
        }
        Thread.sleep(1000);
      }
      // Set the network topology of the other node to the match the network
      // topology of the node that came up.
      int validIdx = info[0].getHostName().equals(hosts[0]) ? 0 : 1;
      int invalidIdx = validIdx == 1 ? 0 : 1;
      StaticMapping.addNodeToRack(hosts[invalidIdx], racks[validIdx]);
      LOG.info("datanode " + validIdx + " came up with network location " + 
        info[0].getNetworkLocation());

      // Restart the DN with the invalid topology and wait for it to register.
      cluster.restartDataNode(invalidIdx);
      
      Thread.sleep(5000);
      while (true) {
        info = client.getDatanodeReport(DatanodeReportType.LIVE);
        if (info.length == 2) {
          break;
        }
        if (info.length == 0) {
          LOG.info("got no valid DNs");
        } else if (info.length == 1) {
          LOG.info("got one valid DN: " + info[0].getHostName() +
              " (at " + info[0].getNetworkLocation() + ")");
        }
        Thread.sleep(1000);
      }
      Assert.assertEquals(info[0].getNetworkLocation(),
                          info[1].getNetworkLocation());
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
}