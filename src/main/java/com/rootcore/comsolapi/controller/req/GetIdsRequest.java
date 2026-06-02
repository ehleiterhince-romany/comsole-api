package com.rootcore.comsolapi.controller.req;

import lombok.Data;
import java.util.List;

/**
 * 辅助测试及内部解析使用的 ID 获取请求实体类。
 * 通过在三维空间中创建一个微小的边界框 (Box Selection)，抓取落入该区域的几何实体 ID。
 */
@Data
public class GetIdsRequest {
    
    /** STEP 几何文件的绝对路径 */
    private String stepFilePath;
    
    /** 需要进行空间匹配的坐标点列表 */
    private List<PointSelectDef> points;

    /**
     * 空间坐标抓取器定义
     */
    @Data
    public static class PointSelectDef {
        /** 自定义标识名称 (内部用于映射) */
        private String name;        
        
        /** 实体维度：0(点), 1(线/边), 2(面), 3(体)。若不传则由业务逻辑推断 */
        private Integer entityDim;  
        
        private Double x;
        private Double y;
        private Double z;
        
        /** 空间搜索的容差范围 (默认如 0.00001) */
        private Double tolerance;   
    }
}