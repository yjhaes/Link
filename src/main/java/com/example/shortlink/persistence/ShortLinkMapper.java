package com.example.shortlink.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLinkEntity> {
    @Select("SELECT short_code, original_url, created_at, expires_at, enabled "
            + "FROM short_link WHERE short_code = #{shortCode} FOR UPDATE")
    ShortLinkEntity selectForUpdate(@Param("shortCode") String shortCode);

    @Update("UPDATE short_link SET enabled = #{enabled} WHERE short_code = #{shortCode}")
    int updateEnabled(@Param("shortCode") String shortCode, @Param("enabled") boolean enabled);
}
