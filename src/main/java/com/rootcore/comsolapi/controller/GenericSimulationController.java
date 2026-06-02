package com.rootcore.comsolapi.controller;

import com.rootcore.comsolapi.controller.req.GenericSimulationRequest;
import com.rootcore.comsolapi.controller.req.GetIdsRequest;
import com.rootcore.comsolapi.service.GenericSimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用仿真 API 接口控制器
 */
@RestController
@RequestMapping("/simulation/generic")
public class GenericSimulationController {

    @Resource
    private GenericSimulationService genericSimulationService;

    /**
     * 测试辅助接口：根据空间坐标反查 COMSOL 内部的实体 ID
     *
     * @param request 包含模型路径及坐标的请求体
     * @return 实体名称与 ID 数组的映射结果
     */
    @PostMapping("/get-ids")
    public ResponseEntity<?> getIdsByCoordinates(@RequestBody GetIdsRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, int[]> ids = genericSimulationService.getIdsByCoordinates(request);
            response.put("code", 200);
            response.put("msg", "Success");
            response.put("data", ids);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取 ID 失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 核心接口：执行一站式动态多物理场仿真
     *
     * @param request 完整的仿真配置请求体
     * @return 仿真结果信息，包含生成的 mph 和 vtu 文件路径
     */
    @PostMapping("/run")
    public ResponseEntity<?> runSimulation(@RequestBody GenericSimulationRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String resultMsg = genericSimulationService.runGenericTask(request);
            response.put("code", 200);
            response.put("msg", "Success");
            response.put("data", resultMsg);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "仿真求解失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 辅助接口：极速网格划分预览
     * 仅生成网格并返回用于前端渲染的 VTU 文件，不进行任何物理求解
     *
     * @param request 网格预览的配置请求体
     * @return 包含生成网格信息的结果对象
     */
    @PostMapping("/mesh-preview")
    public ResponseEntity<?> previewMesh(@RequestBody GenericSimulationRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String resultMsg = genericSimulationService.generateMeshOnly(request);
            response.put("code", 200);
            response.put("msg", "Success");
            response.put("data", resultMsg);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "网格生成失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}