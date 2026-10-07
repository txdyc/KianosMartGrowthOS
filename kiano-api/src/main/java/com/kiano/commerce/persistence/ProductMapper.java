package com.kiano.commerce.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {

    @Select("select c.slug from product_category pc "
            + "join category c on c.id = pc.category_id "
            + "where pc.product_id = #{productId} order by c.slug")
    List<String> selectCategorySlugs(@Param("productId") long productId);
}
