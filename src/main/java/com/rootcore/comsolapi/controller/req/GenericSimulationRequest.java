package com.rootcore.comsolapi.controller.req;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * COMSOL 通用智能仿真请求实体类 (DTO)
 * V14.0 匹配版 - 支持物理域隔离、多维赋参与动态参数
 */
@Data
public class GenericSimulationRequest {

    /**
     * CAD 模型 (Step文件) 在服务器上的绝对物理路径
     */
    private String stepFilePath;

    /**
     * 全局参数字典 (如: freq_start, Env_Temp, Force_Value 等)
     */
    private Map<String, String> globalParameters;

    /**
     * 材料定义列表
     */
    private List<MaterialDef> materials;

    /**
     * 物理场定义列表
     */
    private List<PhysicsDef> physicsList;

    /**
     * 多物理场耦合定义列表 (如: 电磁热、热膨胀等)
     */
    private List<MultiphysicsDef> multiphysicsList;

    /**
     * 求解器研究步配置 (如: Stationary, Frequency-Stationary)
     */
    private StudyDef study;

    /**
     * 后处理导出变量配置
     */
    private List<ExportDef> exports;

    // =================================================================================
    // 内部子类定义 (通过 @Data 自动生成 Get/Set 方法)
    // =================================================================================

    /**
     * 材料定义模型
     */
    @Data
    public static class MaterialDef {
        private String tag;           // 材料唯一标识 (如: mat_1)
        private String name;          // 材料UI展示名称 (如: FR4)
        private Integer entityDim;    // 作用域维度 (3:体, 2:面, 1:线)
        private int[] entities;       // 显式指定的实体ID数组 (如果前端能直接传ID)
        private List<PointSelectDef> pointSelectors; // 坐标系智能抓取器
        private Map<String, Object> properties;      // 材料物理属性字典 (如: E, nu, rho, k)
    }

    /**
     * 🌟 物理场定义模型 (本次核心升级：支持独立作用域)
     */
    @Data
    public static class PhysicsDef {
        private String tag;           // 物理场唯一标识 (如: solid, ht, emw)
        private String type;          // 物理场类型 (如: SolidMechanics)

        // 🌟 新增字段：用于物理域精准隔离 (例如仅在固体上计算力学，排除空气盒)
        private Integer entityDim;
        private int[] entities;
        private List<PointSelectDef> pointSelectors;

        private MeshConfigDef meshConfig;            // 该物理场要求的网格配置
        private List<ConditionDef> conditions;       // 边界条件/载荷列表
    }

    /**
     * 边界条件/载荷定义模型
     */
    @Data
    public static class ConditionDef {
        private String tag;           // 边界标识 (如: fix_1, load_main)
        private String type;          // 边界类型 (如: Fixed, BoundaryLoad, PointLoad)
        private Integer entityDim;    // 作用域维度 (2: 面载荷, 0: 点载荷)
        private int[] entities;       // 显式指定的实体ID
        private List<PointSelectDef> pointSelectors; // 坐标系智能抓取器
        private Map<String, Object> properties;      // 边界属性参数 (如: force, Fp, Tamb)
    }

    /**
     * 多物理场耦合定义模型
     */
    @Data
    public static class MultiphysicsDef {
        private String tag;
        private String type;
        private int[] entities;
    }

    /**
     * 求解器研究定义模型
     */
    @Data
    public static class StudyDef {
        private String type;          // 研究类型
        private Map<String, String> properties; // 求解器扩展属性 (如频率扫描的 plist)
    }

    /**
     * 数据导出配置模型
     */
    @Data
    public static class ExportDef {
        private String expr;          // COMSOL 内部计算表达式 (如: solid.mises, T - 273.15)
        private String descr;         // 前端展示名称 (如: 冯米塞斯应力 (Pa))

        // 默认构造函数
        public ExportDef() {}

        // 全参构造函数
        public ExportDef(String expr, String descr) {
            this.expr = expr;
            this.descr = descr;
        }
    }

    /**
     * 网格配置模型
     */
    @Data
    public static class MeshConfigDef {
        private String label;         // 网格描述
        private Integer size;         // 网格尺寸等级 (1-9，数字越小越密)
    }

    /**
     * 空间坐标智能抓取器 (用于根据坐标拾取几何 ID)
     */
    @Data
    public static class PointSelectDef {
        private String name;          // 抓取器名称 (方便排错)
        private Integer entityDim;    // 目标维度 (3:体, 2:面, 1:边, 0:顶点)
        private Double x;             // X 坐标
        private Double y;             // Y 坐标
        private Double z;             // Z 坐标
        private Double tolerance;     // 抓取容差 (默认 0.00001，点载荷建议设 0.1)
    }
}