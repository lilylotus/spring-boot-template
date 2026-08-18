package com.example.template.mybatis.mapper;

import com.example.template.mybatis.entity.BatchTest;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BatchTestMapper {

    List<BatchTest> queryAll();

    List<BatchTest> query(Integer offset, Integer rows);

    List<BatchTest> queryOptimal(Integer offset, Integer rows);

    List<BatchTest> queryOptimalJoin(Integer offset, Integer rows);

    List<BatchTest> queryOptimalAssociate(Integer offset, Integer rows);

    List<BatchTest> queryOptimalPrimaryId(Integer primaryId, Integer rows);

    Integer insertBatchTest(BatchTest entity);

    Integer updateBatchTestById(Integer id, BatchTest entity);

    Integer batchInsertBatchTest(List<BatchTest> list);

}
