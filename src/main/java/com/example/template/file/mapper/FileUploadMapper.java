package com.example.template.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.template.file.entity.FileUpload;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link FileUpload} 的MyBatis-Plus Mapper，只需要按主键的简单读写，不需要手写XML。
 */
@Mapper
public interface FileUploadMapper extends BaseMapper<FileUpload> {

}
