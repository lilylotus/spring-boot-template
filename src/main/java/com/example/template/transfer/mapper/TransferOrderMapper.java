package com.example.template.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.template.transfer.entity.TransferOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link TransferOrder} 的MyBatis-Plus Mapper，只有单表的插入和按幂等键查询，不需要手写XML。
 * 插入时若幂等键已存在，数据库唯一索引会让 {@code insert} 抛出
 * {@code org.springframework.dao.DuplicateKeyException}，由业务层据此识别重复请求。
 */
@Mapper
public interface TransferOrderMapper extends BaseMapper<TransferOrder> {

    /**
     * 按幂等键查询转账流水，用于判断该幂等键是否已经成功执行过转账。
     *
     * @param idempotencyKey 调用方传入的幂等键
     * @return 已有的转账流水，不存在时返回 {@code null}
     */
    default TransferOrder selectByIdempotencyKey(String idempotencyKey) {
        return selectOne(
            Wrappers.<TransferOrder>lambdaQuery().eq(TransferOrder::getIdempotencyKey, idempotencyKey));
    }
}
