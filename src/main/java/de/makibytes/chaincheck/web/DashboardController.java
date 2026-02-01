/*
 * Copyright (c) 2026 MakiBytes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.makibytes.chaincheck.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.monitor.RpcMonitorService;

@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final NodeRegistry nodeRegistry;
    private final ChainCheckProperties properties;
    private final RpcMonitorService rpcMonitorService;
    private final AppVersionProvider appVersionProvider;

    public DashboardController(DashboardService dashboardService,
                               NodeRegistry nodeRegistry,
                               ChainCheckProperties properties,
                               RpcMonitorService rpcMonitorService,
                               AppVersionProvider appVersionProvider) {
        this.dashboardService = dashboardService;
        this.nodeRegistry = nodeRegistry;
        this.properties = properties;
        this.rpcMonitorService = rpcMonitorService;
        this.appVersionProvider = appVersionProvider;
    }

    @GetMapping({"/", "/dashboard"})
    public String dashboard(@RequestParam(name = "range", required = false) String rangeKey,
                            @RequestParam(name = "node", required = false) String nodeKey,
                            Model model) {
        TimeRange range = TimeRange.fromKey(rangeKey);
        String selectedKey = nodeKey;
        if (selectedKey == null || nodeRegistry.getNode(selectedKey) == null) {
            selectedKey = nodeRegistry.getDefaultNodeKey();
        }
        if (selectedKey == null) {
            model.addAttribute("message", "No RPC nodes configured");
            return "not-found";
        }
        DashboardView view = dashboardService.getDashboard(selectedKey, range);
        NodeDefinition selectedNode = nodeRegistry.getNode(selectedKey);

        model.addAttribute("appName", "ChainCheck");
        model.addAttribute("appTitle", properties.getTitle());
        model.addAttribute("appTitleColor", properties.getTitleColor());
        model.addAttribute("nodeName", selectedNode == null ? "Node" : selectedNode.name());
        model.addAttribute("nodeKey", selectedKey);
        model.addAttribute("nodes", nodeRegistry.getNodes());
        model.addAttribute("referenceNodeKey", rpcMonitorService.getReferenceNodeKey());
        model.addAttribute("range", range);
        model.addAttribute("ranges", TimeRange.values());
        model.addAttribute("view", view);
        model.addAttribute("appVersion", appVersionProvider.getVersion());
        return "dashboard";
    }

    @GetMapping("/anomalies/{id}")
    public String anomaly(@PathVariable("id") long id, Model model) {
        AnomalyEvent event = dashboardService.getAnomaly(id);
        if (event == null) {
            model.addAttribute("message", "Anomaly not found");
            return "not-found";
        }
        model.addAttribute("appName", "ChainCheck");
        model.addAttribute("appTitle", properties.getTitle());
        model.addAttribute("appTitleColor", properties.getTitleColor());
        NodeDefinition selectedNode = nodeRegistry.getNode(event.getNodeKey());
        model.addAttribute("nodeName", selectedNode == null ? "Node" : selectedNode.name());
        model.addAttribute("nodeKey", event.getNodeKey());
        model.addAttribute("event", event);
        return "fragments/anomaly";
    }

    @GetMapping("/api/anomalies/{id}")
    @ResponseBody
    public ResponseEntity<AnomalyDetails> anomalyDetails(@PathVariable("id") long id) {
        AnomalyEvent event = dashboardService.getAnomaly(id);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        NodeDefinition selectedNode = nodeRegistry.getNode(event.getNodeKey());
        String nodeName = selectedNode == null ? "Node" : selectedNode.name();
        AnomalyDetails details = new AnomalyDetails(
                event.getId(),
                event.getNodeKey(),
                nodeName,
                event.getTimestamp(),
                event.getSource().name(),
                event.getType().name(),
                event.getMessage(),
                event.getBlockNumber(),
                event.getBlockHash(),
                event.getParentHash(),
                event.getDetails());
        return ResponseEntity.ok(details);
    }
}
