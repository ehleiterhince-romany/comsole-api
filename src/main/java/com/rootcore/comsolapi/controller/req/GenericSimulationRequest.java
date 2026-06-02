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
        /** 材料唯一标识 (如: mat_1) */
        private String tag;
        /** 材料UI展示名称 (如: FR4) */
        private String name;
        /** 作用域维度 (3:体, 2:面, 1:线) */
        private Integer entityDim;
        /** 显式指定的实体ID数组 (如果前端能直接传ID) */
        private int[] entities;
        /** 坐标系智能抓取器 */
        private List<PointSelectDef> pointSelectors;
        /** 材料物理属性字典 (如: E, nu, rho, k) */
        private Map<String, Object> properties;
    }

    /**
     * 物理场定义模型 (支持独立作用域)
     */
    @Data
    public static class PhysicsDef {
        /** 物理场唯一标识 (如: solid, ht, emw) */
        private String tag;
        /** 物理场类型 (如: SolidMechanics) */
        private String type;

        /** 用于物理域精准隔离的实体维度 (例如仅在固体上计算力学，排除空气盒) */
        private Integer entityDim;
        /** 显式指定的实体ID数组 */
        private int[] entities;
        /** 坐标系智能抓取器 */
        private List<PointSelectDef> pointSelectors;

        /** 该物理场要求的网格配置 */
        private MeshConfigDef meshConfig;
        /** 边界条件/载荷列表 */
        private List<ConditionDef> conditions;
    }

    /**
     * 边界条件/载荷定义模型
     */
    @Data
    public static class ConditionDef {
        /** 边界标识 (如: fix_1, load_main) */
        private String tag;
        /** 边界类型 (如: Fixed, BoundaryLoad, PointLoad) */
        private String type;
        /** 作用域维度 (2: 面载荷, 0: 点载荷) */
        private Integer entityDim;
        /** 显式指定的实体ID */
        private int[] entities;
        /** 坐标系智能抓取器 */
        private List<PointSelectDef> pointSelectors;
        /** 边界属性参数 (如: force, Fp, Tamb) */
        private Map<String, Object> properties;
    }

    /**
     * 多物理场耦合定义模型
     */
    @Data
    public static class MultiphysicsDef {
        /** 耦合唯一标识 */
        private String tag;
        /** 耦合类型 */
        private String type;
        /** 作用域实体ID数组 */
        private int[] entities;
    }

    /**
     * 求解器研究定义模型
     */
    @Data
    public static class StudyDef {
        /** 研究类型 */
        private String type;
        /** 求解器扩展属性 (如频率扫描的 plist) */
        private Map<String, String> properties;
    }

    /**
     * 数据导出配置模型
     */
    @Data
    public static class ExportDef {
        /** COMSOL 内部计算表达式 (如: solid.mises, T - 273.15) */
        private String expr;
        /** 前端展示名称 (如: 冯米塞斯应力 (Pa)) */
        private String descr;

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
        /** 网格描述 */
        private String label;
        /** 网格尺寸等级 (1-9，数字越小越密) */
        private Integer size;
    }

    /**
     * 空间坐标智能抓取器 (用于根据坐标拾取几何 ID)
     */
    @Data
    public static class PointSelectDef {
        /** 抓取器名称 (方便排错) */
        private String name;
        /** 目标维度 (3:体, 2:面, 1:边, 0:顶点) */
        private Integer entityDim;
        /** X 轴坐标 */
        private Double x;
        /** Y 轴坐标 */
        private Double y;
        /** Z 轴坐标 */
        private Double z;
        /** 抓取容差 (默认 0.00001，点载荷建议设 0.1) */
        private Double tolerance;
    }
}