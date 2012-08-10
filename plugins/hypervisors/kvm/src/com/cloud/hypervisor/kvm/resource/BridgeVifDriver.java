/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.exception.InternalErrorException;
import com.cloud.network.Networks;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import org.apache.log4j.Logger;
import org.libvirt.LibvirtException;

import javax.naming.ConfigurationException;
import java.net.URI;
import java.util.Map;

public class BridgeVifDriver extends VifDriverBase {

    private static final Logger s_logger = Logger
            .getLogger(BridgeVifDriver.class);
    private int _timeout;
    private String _modifyVlanPath;

    @Override
    public void configure(Map<String, Object> params) throws ConfigurationException {

        super.configure(params);

        // Set the domr scripts directory
        params.put("domr.scripts.dir", "scripts/network/domr/kvm");


        String networkScriptsDir = (String) params.get("network.scripts.dir");
        if (networkScriptsDir == null) {
            networkScriptsDir = "scripts/vm/network/vnet";
        }

        String value = (String) params.get("scripts.timeout");
        _timeout = NumbersUtil.parseInt(value, 30 * 60) * 1000;

        _modifyVlanPath = Script.findScript(networkScriptsDir, "modifyvlan.sh");
        if (_modifyVlanPath == null) {
            throw new ConfigurationException("Unable to find modifyvlan.sh");
        }

        try {
            createControlNetwork();
        } catch (LibvirtException e) {
            throw new ConfigurationException(e.getMessage());
        }
    }

    @Override
    public LibvirtVMDef.InterfaceDef plug(NicTO nic, String guestOsType)
            throws InternalErrorException, LibvirtException {

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("nic=" + nic);
        }

        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();

        String vlanId = null;
        if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vlan) {
            URI broadcastUri = nic.getBroadcastUri();
            vlanId = broadcastUri.getHost();
        }
        if (nic.getType() == Networks.TrafficType.Guest) {
            if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vlan
                    && !vlanId.equalsIgnoreCase("untagged")) {
                String brName = createVlanBr(vlanId, _pifs.get("private"));
                intf.defBridgeNet(brName, null, nic.getMac(), getGuestNicModel(guestOsType));
            } else {
                intf.defBridgeNet(_bridges.get("guest"), null, nic.getMac(), getGuestNicModel(guestOsType));
            }
        } else if (nic.getType() == Networks.TrafficType.Control) {
            /* Make sure the network is still there */
            createControlNetwork();
            intf.defBridgeNet(_bridges.get("linklocal"), null, nic.getMac(), getGuestNicModel(guestOsType));
        } else if (nic.getType() == Networks.TrafficType.Public) {
            if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vlan
                    && !vlanId.equalsIgnoreCase("untagged")) {
                String brName = createVlanBr(vlanId, _pifs.get("public"));
                intf.defBridgeNet(brName, null, nic.getMac(), getGuestNicModel(guestOsType));
            } else {
                intf.defBridgeNet(_bridges.get("public"), null, nic.getMac(), getGuestNicModel(guestOsType));
            }
        } else if (nic.getType() == Networks.TrafficType.Management) {
            intf.defBridgeNet(_bridges.get("private"), null, nic.getMac(), getGuestNicModel(guestOsType));
        } else if (nic.getType() == Networks.TrafficType.Storage) {
            String storageBrName = nic.getName() == null ? _bridges.get("private")
                    : nic.getName();
            intf.defBridgeNet(storageBrName, null, nic.getMac(), getGuestNicModel(guestOsType));
        }
        return intf;
    }

    @Override
    public void unplug(LibvirtVMDef.InterfaceDef iface) {
        // Nothing needed as libvirt cleans up tap interface from bridge.
    }

    private String setVnetBrName(String vnetId) {
        return "cloudVirBr" + vnetId;
    }

    private String createVlanBr(String vlanId, String nic)
            throws InternalErrorException {
        String brName = setVnetBrName(vlanId);
        createVnet(vlanId, nic);
        return brName;
    }

    private void createVnet(String vnetId, String pif)
            throws InternalErrorException {
        final Script command = new Script(_modifyVlanPath, _timeout, s_logger);
        command.add("-v", vnetId);
        command.add("-p", pif);
        command.add("-o", "add");

        final String result = command.execute();
        if (result != null) {
            throw new InternalErrorException("Failed to create vnet " + vnetId
                    + ": " + result);
        }
    }

    private void createControlNetwork() throws LibvirtException {
        createControlNetwork(_bridges.get("linklocal"));
    }

    private void deletExitingLinkLocalRoutTable(String linkLocalBr) {
        Script command = new Script("/bin/bash", _timeout);
        command.add("-c");
        command.add("ip route | grep " + NetUtils.getLinkLocalCIDR());
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        boolean foundLinkLocalBr = false;
        if (result == null && parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                String[] tokens = line.split(" ");
                if (!tokens[2].equalsIgnoreCase(linkLocalBr)) {
                    Script.runSimpleBashScript("ip route del " + NetUtils.getLinkLocalCIDR());
                } else {
                    foundLinkLocalBr = true;
                }
            }
        }
        if (!foundLinkLocalBr) {
            Script.runSimpleBashScript("ifconfig " + linkLocalBr + " 169.254.0.1;" + "ip route add " +
                    NetUtils.getLinkLocalCIDR() + " dev " + linkLocalBr + " src " + NetUtils.getLinkLocalGateway());
        }
    }

    private void createControlNetwork(String privBrName) {
        deletExitingLinkLocalRoutTable(privBrName);
        if (!isBridgeExists(privBrName)) {
            Script.runSimpleBashScript("brctl addbr " + privBrName + "; ifconfig " + privBrName + " up; ifconfig " +
                    privBrName + " 169.254.0.1", _timeout);
        }

    }

    private boolean isBridgeExists(String bridgeName) {
        Script command = new Script("/bin/sh", _timeout);
        command.add("-c");
        command.add("brctl show|grep " + bridgeName);
        final OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        String result = command.execute(parser);
        if (result != null || parser.getLine() == null) {
            return false;
        } else {
            return true;
        }
    }
}
