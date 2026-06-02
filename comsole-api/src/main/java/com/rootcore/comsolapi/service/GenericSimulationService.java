package com.rootcore.comsolapi.service;

import com.comsol.model.*;
import com.comsol.model.physics.PhysicsFeature;
import com.comsol.model.physics.MultiphysicsCoupling;
import com.comsol.model.util.ModelUtil;
import com.rootcore.comsolapi.controller.req.GenericSimulationRequest;
import com.rootcore.comsolapi.controller.req.GetIdsRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * COMSOL 通用智能仿真业务逻辑引擎 (V14.0 物理域隔离终极版)
 * <p>
 * 本引擎核心架构特性：
 * <ul>
 * <li><b>物理域精准隔离：</b>支持在物理场级别 (Physics) 配置坐标拾取器，将特定物理场(如固体力学)严格限制在指定零件上，彻底排除流体/空气盒的干扰。</li>
 * <li><b>极速内联解析：</b>采用一站式几何布尔抓取（Box），彻底消灭二次导入。</li>
 * <li><b>多维材料赋参：</b>支持材料维度的动态降级，允许向 2D 面或 3D 体独立赋予不同材料库。</li>
 * <li><b>严格逻辑隔离：</b>完美遵循 COMSOL 底层 API 规范，隔离 Multiphysics 与 Physics 的 activate 作用域。</li>
 * <li><b>时序与命名安全保障：</b>采用纯几何网格及动态时间戳 Tag，彻底消除 `ftet1` 命名冲突与高并发残留。</li>
 * <li><b>智能数据流分发：</b>自动拆分 3D体变量(VTU)、2D面变量(VTU)、1D全局曲线(JSON数组)，完美兼容 ParaView 与 ECharts。</li>
 * <li><b>内置模型校验：</b>求解前强制校验坐标命中率及方程编译合法性，拦截“漏赋材料”等经典异常。</li>
 * </ul>
 */
@Service
public class GenericSimulationService {

    @Value("${comsol.base.url}")
    private String comsolBaseUrl;

    @Value("${comsol.base.desdir}")
    private String desdir;

    /**
     * 辅助接口：根据空间三维坐标点获取对应的几何实体 ID。
     * (用于前端校验拾取坐标是否合法)
     */
    public Map<String, int[]> getIdsByCoordinates(GetIdsRequest request) throws Exception {
        System.setProperty("cs.comsoldir", comsolBaseUrl);
        Model model = null;
        Map<String, int[]> resultMap = new HashMap<>();
        try {
            ModelUtil.initStandalone(false);
            model = ModelUtil.create("IdParserModel");
            model.modelNode().create("comp1", true);
            model.geom().create("geom1", 3);
            model.geom("geom1").feature().create("imp1", "Import");
            model.geom("geom1").feature("imp1").set("filename", request.getStepFilePath());
            model.geom("geom1").run();

            if (request.getPoints() != null) {
                for (GetIdsRequest.PointSelectDef pt : request.getPoints()) {
                    model.selection().create(pt.getName(), "Box");
                    int dim = (pt.getEntityDim() != null) ? pt.getEntityDim() : 3;
                    model.selection(pt.getName()).set("entitydim", dim);
                    double tol = (pt.getTolerance() != null) ? pt.getTolerance() : 0.00001;

                    if (pt.getX() != null) {
                        model.selection(pt.getName()).set("xmin", pt.getX() - tol);
                        model.selection(pt.getName()).set("xmax", pt.getX() + tol);
                    }
                    if (pt.getY() != null) {
                        model.selection(pt.getName()).set("ymin", pt.getY() - tol);
                        model.selection(pt.getName()).set("ymax", pt.getY() + tol);
                    }
                    if (pt.getZ() != null) {
                        model.selection(pt.getName()).set("zmin", pt.getZ() - tol);
                        model.selection(pt.getName()).set("zmax", pt.getZ() + tol);
                    }
                    resultMap.put(pt.getName(), model.selection(pt.getName()).entities());
                }
            }
            return resultMap;
        } finally {
            if (model != null) ModelUtil.remove("IdParserModel");
        }
    }

    /**
     * 核心业务接口：执行一站式动态多物理场仿真任务 (内嵌模型校验与多维数据导出)
     */
    public String runGenericTask(GenericSimulationRequest request) throws Exception {

        System.setProperty("cs.comsoldir", comsolBaseUrl);
        // 自动探测服务器核心数，全速并行求解
        int cores = Runtime.getRuntime().availableProcessors();
        System.setProperty("cs.numthreads", String.valueOf(cores));

        Model model = null;
        Map<String, int[]> globalIdMap = new HashMap<>();

        try {
            ModelUtil.initStandalone(false);
            model = ModelUtil.create("ExecutionModel");
            model.modelNode().create("comp1", true);

            // =========================================================
            // [1.1] 注册全局参数 (如频率范围、环境温度、载荷大小等)
            // =========================================================
            if (request.getGlobalParameters() != null) {
                for (Map.Entry<String, String> entry : request.getGlobalParameters().entrySet()) {
                    model.param().set(entry.getKey(), entry.getValue());
                }
            }

            // =========================================================
            // [1.2] 几何导入
            // =========================================================
            model.geom().create("geom1", 3);
            model.geom("geom1").feature().create("imp1", "Import");
            model.geom("geom1").feature("imp1").set("filename", request.getStepFilePath());
            model.geom("geom1").run();

            // =========================================================
            // [2] 空间 ID 拾取 (内联模式预处理)
            // =========================================================
            List<GetIdsRequest.PointSelectDef> idPoints = new ArrayList<>();

            // 2.1 收集材料的选择器
            if (request.getMaterials() != null) {
                for (int i = 0; i < request.getMaterials().size(); i++) {
                    GenericSimulationRequest.MaterialDef mat = request.getMaterials().get(i);
                    if (mat.getPointSelectors() != null) {
                        for (int j = 0; j < mat.getPointSelectors().size(); j++) {
                            int targetDim = (mat.getEntityDim() != null) ? mat.getEntityDim() : 3;
                            idPoints.add(buildIdPoint(mat.getPointSelectors().get(j), "mat_" + mat.getTag() + "_" + j, targetDim));
                        }
                    }
                }
            }

            // 2.2 收集物理场及边界条件的选择器
            if (request.getPhysicsList() != null) {
                for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {

                    // 拦截物理场自身的空间选择器 (用于物理域隔离，例如将力学限定在固体上)
                    if (phys.getPointSelectors() != null) {
                        for (int j = 0; j < phys.getPointSelectors().size(); j++) {
                            int targetDim = (phys.getEntityDim() != null) ? phys.getEntityDim() : 3;
                            idPoints.add(buildIdPoint(phys.getPointSelectors().get(j), "phys_" + phys.getTag() + "_" + j, targetDim));
                        }
                    }

                    // 拦截边界条件的选择器
                    if (phys.getConditions() != null) {
                        for (int j = 0; j < phys.getConditions().size(); j++) {
                            GenericSimulationRequest.ConditionDef cond = phys.getConditions().get(j);
                            if (cond.getPointSelectors() != null) {
                                for (int k = 0; k < cond.getPointSelectors().size(); k++) {
                                    idPoints.add(buildIdPoint(cond.getPointSelectors().get(k), "cond_" + phys.getTag() + "_" + cond.getTag()
                                            + "_" + k, cond.getEntityDim()));
                                }
                            }
                        }
                    }
                }
            }

            // 执行真正的 Box 抓取运算
            for (GetIdsRequest.PointSelectDef pt : idPoints) {
                String boxName = "box_" + pt.getName();
                model.selection().create(boxName, "Box");
                int dim = (pt.getEntityDim() != null) ? pt.getEntityDim() : 3;
                model.selection(boxName).set("entitydim", dim);
                double tol = (pt.getTolerance() != null) ? pt.getTolerance() : 0.00001;

                if (pt.getX() != null) { model.selection(boxName).set("xmin", pt.getX() - tol); model.selection(boxName).set("xmax", pt.getX() + tol); }
                if (pt.getY() != null) { model.selection(boxName).set("ymin", pt.getY() - tol); model.selection(boxName).set("ymax", pt.getY() + tol); }
                if (pt.getZ() != null) { model.selection(boxName).set("zmin", pt.getZ() - tol); model.selection(boxName).set("zmax", pt.getZ() + tol); }

                int[] hitEntities = model.selection(boxName).entities();

                // 几何命中安全拦截
                if (hitEntities == null || hitEntities.length == 0) {
                    throw new Exception("模型校验不通过：坐标提取器 [" + pt.getName() + "] 未命中任何几何实体(Dim=" + dim + ")，请检查前端拾取坐标是否准确。");
                }

                globalIdMap.put(pt.getName(), hitEntities);
                model.selection().remove(boxName);
            }

            // =========================================================
            // [3] 材料字典映射与实体赋参
            // =========================================================
            if (request.getMaterials() != null) {
                for (GenericSimulationRequest.MaterialDef matDef : request.getMaterials()) {
                    Material mat = model.component("comp1").material().create(matDef.getTag(), "Common");
                    mat.label(matDef.getName());

                    if (matDef.getEntityDim() != null) {
                        mat.selection().geom("geom1", matDef.getEntityDim());
                    }

                    int[] mergedIds = resolveIds(matDef.getEntities(), matDef.getPointSelectors(), "mat_" + matDef.getTag(), globalIdMap);
                    if (mergedIds.length > 0) mat.selection().set(mergedIds);
                    else mat.selection().all();

                    if (matDef.getProperties() != null) {
                        for (Map.Entry<String, Object> prop : matDef.getProperties().entrySet()) {
                            if (prop.getValue() instanceof List) {
                                String[] strArray = ((List<?>) prop.getValue()).stream().map(String::valueOf).toArray(String[]::new);
                                mat.propertyGroup("def").set(getKey(prop.getKey()), strArray);
                            } else {
                                mat.propertyGroup("def").set(getKey(prop.getKey()), String.valueOf(prop.getValue()));
                            }
                        }
                    }
                }
            }

            // =========================================================
            // [4] 单物理场与多物理场耦合构建
            // =========================================================
            if (request.getPhysicsList() != null) {
                for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {
                    model.physics().create(phys.getTag(), phys.getType(), "geom1");

                    // 1. 先尝试获取前端显式指定的物理场作用域
                    int[] physIds = resolveIds(phys.getEntities(), phys.getPointSelectors(), "phys_" + phys.getTag(), globalIdMap);

                    // 2. 自动域隔离拦截器：如果前端没传，且是力学/热学，自动嗅探固体材料并剔除空气！
                    if (physIds.length == 0 && ("SolidMechanics".equals(phys.getType()) || "HeatTransfer".equals(phys.getType())
                            || "HeatTransferInSolids".equals(phys.getType()))) {
                        Set<Integer> autoSolidIds = new HashSet<>();
                        if (request.getMaterials() != null) {
                            for (int i = 0; i < request.getMaterials().size(); i++) {
                                GenericSimulationRequest.MaterialDef mat = request.getMaterials().get(i);
                                String matName = (mat.getName() != null) ? mat.getName().toLowerCase() : "";

                                // 智能判别：只要名字里没有空气或流体，就认为是固体
                                if (!matName.contains("air") && !matName.contains("空气") && !matName.contains("fluid") && !matName.contains("流体")) {
                                    // 把这个固体材料覆盖的 ID 收集起来
                                    int[] mIds = resolveIds(mat.getEntities(), mat.getPointSelectors(), "mat_" + mat.getTag(), globalIdMap);
                                    for (int id : mIds) autoSolidIds.add(id);
                                }
                            }
                        }
                        if (!autoSolidIds.isEmpty()) {
                            physIds = autoSolidIds.stream().mapToInt(Integer::intValue).toArray();
                            System.out.println("🤖 自动域隔离生效！已为 [" + phys.getType() + "] 自动剔除空气，仅绑定固体实体 ID: " + Arrays.toString(physIds));
                        }
                    }

                    // 3. 将计算好的 ID 赋予物理场
                    if (physIds.length > 0) {
                        model.physics(phys.getTag()).selection().set(physIds);
                    }

                    // ... 以下处理边界条件 (conditions) 的代码保持完全不变 ...
                    if (phys.getConditions() != null) {
                        for (GenericSimulationRequest.ConditionDef cond : phys.getConditions()) {
                            PhysicsFeature feature = (cond.getEntityDim() != null)
                                    ? model.physics(phys.getTag()).create(cond.getTag(), cond.getType(), cond.getEntityDim())
                                    : model.physics(phys.getTag()).create(cond.getTag(), cond.getType());

                            int[] mergedIds = resolveIds(cond.getEntities(), cond.getPointSelectors(), "cond_" + phys.getTag() + "_" + cond.getTag(), globalIdMap);
                            if (mergedIds.length > 0) feature.selection().set(mergedIds);

                            if (cond.getProperties() != null) {
                                for (Map.Entry<String, Object> prop : cond.getProperties().entrySet()) {
                                    if (prop.getValue() instanceof List) {
                                        String[] strArray = ((List<?>) prop.getValue()).stream().map(String::valueOf).toArray(String[]::new);
                                        feature.set(prop.getKey(), strArray);
                                    } else {
                                        feature.set(prop.getKey(), String.valueOf(prop.getValue()));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (request.getMultiphysicsList() != null) {
                for (GenericSimulationRequest.MultiphysicsDef mp : request.getMultiphysicsList()) {
                    MultiphysicsCoupling mpFeature = model.multiphysics().create(mp.getTag(), mp.getType(), "geom1");
                    if (mp.getEntities() != null && mp.getEntities().length > 0) mpFeature.selection().set(mp.getEntities());
                    else mpFeature.selection().all();
                }
            }

            // =========================================================
            // [5] 构建研究步(Study)
            // =========================================================
            model.study().create("std1");
            String studyType = (request.getStudy() != null && request.getStudy().getType() != null) ? request.getStudy().getType() : "Stationary";

            if ("Frequency-Stationary".equalsIgnoreCase(studyType)) {
                model.study("std1").create("step1", "Frequency");
                List<String> act1 = new ArrayList<>();
                List<String> eqForm1 = new ArrayList<>();

                if (request.getPhysicsList() != null) {
                    for (GenericSimulationRequest.PhysicsDef p : request.getPhysicsList()) {
                        act1.add(p.getTag());
                        act1.add(p.getType().contains("ElectromagneticWaves") ? "on" : "off");
                        if (p.getType().contains("SolidMechanics") || p.getType().contains("HeatTransfer")) {
                            eqForm1.add(p.getTag());
                            eqForm1.add("Stationary");
                        }
                    }
                }
                model.study("std1").feature("step1").set("activate", act1.toArray(new String[0]));
                if (!eqForm1.isEmpty()) model.study("std1").feature("step1").set("equationform", eqForm1.toArray(new String[0]));
                if (request.getStudy().getProperties() != null) {
                    for (Map.Entry<String, String> entry : request.getStudy().getProperties().entrySet()) {
                        model.study("std1").feature("step1").set(entry.getKey(), entry.getValue());
                    }
                }

                model.study("std1").create("step2", "Stationary");
                List<String> act2 = new ArrayList<>();
                if (request.getPhysicsList() != null) {
                    for (GenericSimulationRequest.PhysicsDef p : request.getPhysicsList()) {
                        act2.add(p.getTag());
                        act2.add(p.getType().contains("ElectromagneticWaves") ? "off" : "on");
                    }
                }
                model.study("std1").feature("step2").set("activate", act2.toArray(new String[0]));

            }else if ("EM-Thermal-EM".equalsIgnoreCase(studyType)) {
                // 🌟 [宏指令核心] 专属定制：微波器件热漂移三步联合求解 (智能动态版)
                System.out.println("🤖 触发高级宏指令：执行 [电磁(形变前) -> 热力稳态 -> 电磁(形变后)] 三步耦合求解");

                // 1. 智能推导各步骤的激活状态矩阵
                List<String> act1 = new ArrayList<>(); // Step 1: 仅开启电磁
                List<String> act2 = new ArrayList<>(); // Step 2: 仅开启热、力
                List<String> act3 = new ArrayList<>(); // Step 3: 仅开启电磁

                // 🌟 修复：只对“基础物理场”进行硬控开关，绝不将“多物理场”混入 activate 数组！
                if (request.getPhysicsList() != null) {
                    for (GenericSimulationRequest.PhysicsDef p : request.getPhysicsList()) {
                        // 智能嗅探：只要类型里带 Electromagnetic 就算电磁场
                        boolean isEM = p.getType().contains("Electromagnetic") || p.getType().contains("emw");

                        act1.add(p.getTag()); act1.add(isEM ? "on" : "off");
                        act2.add(p.getTag()); act2.add(!isEM ? "on" : "off");
                        act3.add(p.getTag()); act3.add(isEM ? "on" : "off");
                    }
                }

                // ⚠️ 注意：这里彻底删除了遍历 request.getMultiphysicsList() 并加入 act 数组的逻辑！
                // COMSOL 底层引擎会自动根据上下步的物理场状态，智能接管多物理场 (eh1, te1) 的变量映射与激活。

                // 提取前端传来的频率扫描范围
                String plist = (request.getStudy().getProperties() != null) ? request.getStudy().getProperties().get("plist") : null;

                model.study("std1").create("step1", "Frequency");
                model.study("std1").feature("step1").set("activate", act1.toArray(new String[0]));
                if (plist != null) model.study("std1").feature("step1").set("plist", plist);

                // === Step 2: 热力稳态 (Stationary) ===
                model.study("std1").create("step2", "Stationary");
                model.study("std1").feature("step2").set("activate", act2.toArray(new String[0]));
                // 🌟 [终极修复] 彻底删除 nlin/geomnonlin 这行！
                // 依靠 COMSOL 底层自带的 Material -> Spatial Frame 映射机制自动传递网格形变！

                // === Step 3: 形变后电磁场 (Frequency) ===
                model.study("std1").create("step3", "Frequency");
                model.study("std1").feature("step3").set("activate", act3.toArray(new String[0]));
                if (plist != null) model.study("std1").feature("step3").set("plist", plist);

            } else {
                model.study("std1").create("step1", studyType);
                if (request.getStudy() != null && request.getStudy().getProperties() != null) {
                    for (Map.Entry<String, String> entry : request.getStudy().getProperties().entrySet()) {
                        model.study("std1").feature("step1").set(entry.getKey(), entry.getValue());
                    }
                }
            }

            // =========================================================
            // [6] 执行纯几何网格划分 (带动态防撞车 Tag)
            // =========================================================
            model.mesh().create("mesh1", "geom1");
            model.mesh("mesh1").automatic(false);
            int targetSize = 5;
            if (request.getPhysicsList() != null) {
                for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {
                    if (phys.getMeshConfig() != null && phys.getMeshConfig().getSize() != null) {
                        targetSize = Math.min(targetSize, phys.getMeshConfig().getSize());
                    }
                }
            }
            model.mesh("mesh1").feature("size").set("hauto", targetSize);
            String tetTag = "ftet_" + System.currentTimeMillis();
            model.mesh("mesh1").feature().create(tetTag, "FreeTet");
            model.mesh("mesh1").run();

            // =========================================================
            // [7] 构建实际的求解序列并运行
            // =========================================================
            model.sol().create("sol1");
            model.sol("sol1").attach("std1");
            model.sol("sol1").createAutoSequence("std1");

            // 方程编译与求解异常智能拦截
            try {
                model.sol("sol1").runAll();
            } catch (Exception e) {
                e.printStackTrace();
                String errMsg = e.getMessage();
                if (errMsg != null && (errMsg.contains("未定义") || errMsg.contains("未知参数") || errMsg.contains("Undefined") || errMsg.contains("Unknown"))) {
                    throw new Exception("模型校验不通过：物理场方程编译失败！\n请排查：\n1. 零件是否漏赋了材料，或材料缺少核心属性(如E, nu)？\n2. 空气盒是否已被正确排除出力学计算域？\n[COMSOL底层报错]: " + errMsg);
                } else {
                    throw new Exception("模型求解异常：" + errMsg);
                }
            }

            // =========================================================
            // [8] 后处理: 渲染隔离与 UI 图表树生成
            // =========================================================
            Files.createDirectories(Paths.get(desdir));
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String mphPath = desdir + "/output_" + timestamp + ".mph";

            boolean generatePlots = true;
            if (request.getGlobalParameters() != null && "false".equalsIgnoreCase(request.getGlobalParameters().get("generatePlots"))) {
                generatePlots = false;
            }

            if (generatePlots && request.getPhysicsList() != null) {
                for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {
                    try {
                        String physTag = phys.getTag();
                        String pgTag = "pg_3d_" + physTag;

                        if ("SolidMechanics".equals(phys.getType())) {
                            model.result().create(pgTag, "PlotGroup3D");
                            model.result(pgTag).label("应力 (" + physTag + ")");
                            model.result(pgTag).set("frametype", "spatial");
                            model.result(pgTag).create("vol1", "Volume");
                            model.result(pgTag).feature("vol1").set("expr", physTag + ".mises");
                            model.result(pgTag).feature("vol1").set("colortable", "Prism");
                            model.result(pgTag).feature("vol1").create("def1", "Deform");
                            model.result(pgTag).feature("vol1").feature("def1").set("scaleactive", false);
                            model.result(pgTag).feature("vol1").feature("def1").set("scale", 1);
                            model.result(pgTag).run();
                        }
                    } catch (Exception e) {
                        System.err.println("跳过图组 " + phys.getTag() + " 的渲染: " + e.getMessage());
                    }
                }
            }
            model.save(mphPath);

            // =========================================================
            // [9] 数据转换: 动态全维度结果提取 (3D体 + 2D面 + 1D全局数值)
            // =========================================================
            try {
                boolean hasSolidMechanics = false;
                if (request.getPhysicsList() != null) {
                    for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {
                        if ("SolidMechanics".equals(phys.getType())) {
                            hasSolidMechanics = true;
                            break;
                        }
                    }
                }

                List<GenericSimulationRequest.ExportDef> domainExports = new ArrayList<>();
                List<GenericSimulationRequest.ExportDef> surfaceExports = new ArrayList<>();
                List<GenericSimulationRequest.ExportDef> globalExports = new ArrayList<>();

                if (hasSolidMechanics) {
                    GenericSimulationRequest.ExportDef defX = new GenericSimulationRequest.ExportDef("x + u", "Deformed_X");
                    GenericSimulationRequest.ExportDef defY = new GenericSimulationRequest.ExportDef("y + v", "Deformed_Y");
                    GenericSimulationRequest.ExportDef defZ = new GenericSimulationRequest.ExportDef("z + w", "Deformed_Z");
                    domainExports.addAll(Arrays.asList(defX, defY, defZ));
                    surfaceExports.addAll(Arrays.asList(defX, defY, defZ));
                }

                if (request.getExports() != null) {
                    for (GenericSimulationRequest.ExportDef exp : request.getExports()) {
                        String expr = exp.getExpr();
                        if (expr.contains("S11") || expr.contains("S21") || expr.contains("S12") || expr.contains("S22") || expr.contains("dB") || expr.contains("freq")) {
                            globalExports.add(exp);
                        } else if (expr.contains(".q0") || expr.contains(".rflux") || expr.contains(".nflux")) {
                            surfaceExports.add(exp);
                        } else {
                            domainExports.add(exp);
                            surfaceExports.add(exp);
                        }
                    }
                }

                // 🌟 2. 导出 3D 体数据集 (生成 _3d.vtu)
                String vtkPath3D = desdir + "/output_" + timestamp + "_3d.vtu";
                if (!domainExports.isEmpty()) {
                    model.result().export().create("data3d", "Data");
                    model.result().export("data3d").set("filename", vtkPath3D);
                    model.result().export("data3d").set("data", "dset1");

                    // 🌟 [黑科技核心] 兼容性防御：尝试请求“全序列导出”
                    try {
                        model.result().export("data3d").set("looplevelinput", new String[]{"all"});
                    } catch (Exception e) {
                        // 如果当前是纯稳态单步求解(没有频率/参数扫描)，设置该属性会抛错。
                        // 直接静默忽略，COMSOL 会自动导出默认的单步解，完美自洽！
                    }

                    for (int i = 0; i < domainExports.size(); i++) {
                        model.result().export("data3d").setIndex("expr", domainExports.get(i).getExpr(), i);
                        model.result().export("data3d").setIndex("descr", domainExports.get(i).getDescr(), i);
                    }
                    model.result().export("data3d").run();
                }

                // 🌟 3. 导出 2D 面数据集 (生成 _2d.vtu)
                String vtkPath2D = desdir + "/output_" + timestamp + "_2d.vtu";
                if (!surfaceExports.isEmpty()) {
                    String surfDset = "dset_surf_" + System.currentTimeMillis();
                    model.result().dataset().create(surfDset, "Solution");
                    model.result().dataset(surfDset).set("solution", "sol1");

                    String geomTag = model.geom().tags()[0];
                    model.result().dataset(surfDset).selection().geom(geomTag, 2);
                    model.result().dataset(surfDset).selection().all();

                    model.result().export().create("data2d", "Data");
                    model.result().export("data2d").set("filename", vtkPath2D);
                    model.result().export("data2d").set("data", surfDset);

                    // 🌟 [黑科技核心] 兼容性防御：尝试请求“全序列导出”
                    try {
                        model.result().export("data2d").set("looplevelinput", new String[]{"all"});
                    } catch (Exception e) {
                        // 静默忽略
                    }

                    for (int i = 0; i < surfaceExports.size(); i++) {
                        model.result().export("data2d").setIndex("expr", surfaceExports.get(i).getExpr(), i);
                        model.result().export("data2d").setIndex("descr", surfaceExports.get(i).getDescr(), i);
                    }
                    model.result().export("data2d").run();
                }

                String sParamResultStr = "";
                if (!globalExports.isEmpty()) {
                    try {
                        String evalTag = "gev_" + System.currentTimeMillis();
                        model.result().numerical().create(evalTag, "EvalGlobal");
                        model.result().numerical(evalTag).set("data", "dset1");
                        String[] globalExprs = new String[globalExports.size()];
                        for (int i = 0; i < globalExports.size(); i++) {
                            globalExprs[i] = globalExports.get(i).getExpr();
                        }
                        model.result().numerical(evalTag).set("expr", globalExprs);
                        double[][] realData = model.result().numerical(evalTag).getReal();

                        StringBuilder sb = new StringBuilder("\n\n=== 全局曲线数据 (前端用于 ECharts) ===\n{");
                        for (int i = 0; i < globalExports.size(); i++) {
                            sb.append("\n  \"").append(globalExports.get(i).getExpr()).append("\": [");
                            for(int j=0; j < realData[i].length; j++) {
                                sb.append(String.format(Locale.US, "%.4f", realData[i][j]));
                                if(j < realData[i].length - 1) sb.append(", ");
                            }
                            sb.append("]");
                            if(i < globalExports.size() - 1) sb.append(",");
                        }
                        sb.append("\n}");
                        sParamResultStr = sb.toString();
                    } catch (Exception e) {
                        sParamResultStr = "\n[全局变量提取失败]: " + e.getMessage();
                    }
                }

                return "仿真执行成功！\n源模型: " + mphPath +
                        "\n前端3D渲染文件: " + vtkPath3D +
                        "\n前端2D渲染文件: " + vtkPath2D +
                        sParamResultStr;

            } catch (Exception e) {
                return "仿真执行成功，但结果提取异常: " + e.getMessage();
            }

        } finally {
            if (model != null) ModelUtil.remove("ExecutionModel");
        }
    }

    /**
     * 极速网格生成专用引擎 (用于前端秒级预览)
     */
    public String generateMeshOnly(GenericSimulationRequest request) throws Exception {
        System.setProperty("cs.comsoldir", comsolBaseUrl);
        Model model = null;

        try {
            ModelUtil.initStandalone(false);
            model = ModelUtil.create("MeshPreviewModel");
            model.modelNode().create("comp1", true);

            model.geom().create("geom1", 3);
            model.geom("geom1").feature().create("imp1", "Import");
            model.geom("geom1").feature("imp1").set("filename", request.getStepFilePath());
            model.geom("geom1").run();

            int targetSize = 5;
            if (request.getPhysicsList() != null) {
                for (GenericSimulationRequest.PhysicsDef phys : request.getPhysicsList()) {
                    if (phys.getMeshConfig() != null && phys.getMeshConfig().getSize() != null) {
                        targetSize = Math.min(targetSize, phys.getMeshConfig().getSize());
                    }
                }
            }

            model.mesh().create("mesh1", "geom1");
            model.mesh("mesh1").automatic(false);
            model.mesh("mesh1").feature("size").set("hauto", targetSize);
            String tetTag = "ftet_" + System.currentTimeMillis();
            model.mesh("mesh1").feature().create(tetTag, "FreeTet");
            model.mesh("mesh1").run();

            Files.createDirectories(Paths.get(desdir));
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String mphPath = desdir + "/mesh_preview_" + timestamp + ".mph";
            String vtkPath = desdir + "/mesh_preview_" + timestamp + ".vtu";

            try {
                model.result().dataset().create("meshDset", "Mesh");
                model.result().dataset("meshDset").set("mesh", "mesh1");
                model.result().export().create("meshExport", "Data");
                model.result().export("meshExport").set("data", "meshDset");
                model.result().export("meshExport").set("filename", vtkPath);
                model.result().export("meshExport").run();
            } catch (Exception e) {
                System.err.println("网格 VTU 导出异常: " + e.getMessage());
            }

            model.save(mphPath);
            return "网格划分成功！\n源模型文件: " + mphPath + "\n前端渲染网格文件: " + vtkPath;

        } finally {
            if (model != null) ModelUtil.remove("MeshPreviewModel");
        }
    }

    /**
     * 辅助方法：构建坐标选取器
     */
    private GetIdsRequest.PointSelectDef buildIdPoint(GenericSimulationRequest.PointSelectDef pt, String name, Integer dim) {
        GetIdsRequest.PointSelectDef idPt = new GetIdsRequest.PointSelectDef();
        idPt.setName(name);
        idPt.setEntityDim((pt.getEntityDim() != null) ? pt.getEntityDim() : dim);
        idPt.setX(pt.getX()); idPt.setY(pt.getY()); idPt.setZ(pt.getZ());
        idPt.setTolerance(pt.getTolerance());
        return idPt;
    }

    /**
     * 辅助方法：合并隐式声明的 ID 和坐标命中的 ID
     */
    private int[] resolveIds(int[] explicitIds, List<GenericSimulationRequest.PointSelectDef> pts, String prefix, Map<String, int[]> globalIdMap) {
        Set<Integer> finalIds = new HashSet<>();
        if (explicitIds != null) for (int id : explicitIds) finalIds.add(id);
        if (pts != null) {
            for (int i = 0; i < pts.size(); i++) {
                int[] ids = globalIdMap.get(prefix + "_" + i);
                if (ids != null) for (int id : ids) finalIds.add(id);
            }
        }
        return finalIds.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 辅助方法：API 属性命名映射
     */
    private static String getKey(String k) {
        switch (k.toLowerCase()) {
            case "e": return "youngsmodulus";
            case "nu": return "poissonsratio";
            case "rho": return "density";
            case "k": return "thermalconductivity";
            case "cp": return "heatcapacity";
            case "alpha": return "thermalexpansioncoefficient";
            case "epsilon_r": return "relpermittivity";
            case "mu_r": return "relpermeability";
            case "sigma": return "electricconductivity";
            default: return k.toLowerCase();
        }
    }
}