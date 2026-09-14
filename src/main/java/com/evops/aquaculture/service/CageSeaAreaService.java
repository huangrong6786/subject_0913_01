package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.CageSeaAreaRequest;
import com.evops.aquaculture.entity.CageSeaArea;
import com.evops.aquaculture.mapper.CageSeaAreaMapper;
import com.evops.common.BusinessException;
import com.evops.common.util.TzUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 网箱海区档案维护：网箱 → 海区/业务时区。
 * 档案是“按对象时区归算”的时区来源；调整档案只影响之后登记的潮次，
 * 历史潮次已快照当时的海区与时区。
 */
@Service
public class CageSeaAreaService {

    private final CageSeaAreaMapper cageSeaAreaMapper;

    public CageSeaAreaService(CageSeaAreaMapper cageSeaAreaMapper) {
        this.cageSeaAreaMapper = cageSeaAreaMapper;
    }

    /** 登记网箱海区档案；cageNo 为关键业务键必须唯一。 */
    @Transactional
    public CageSeaArea create(CageSeaAreaRequest request) {
        TzUtils.requireZone(request.getTimeZone());
        CageSeaArea existing = findByCageNo(request.getCageNo());
        if (existing != null) {
            throw new BusinessException("网箱已登记海区档案: " + request.getCageNo());
        }
        CageSeaArea entity = new CageSeaArea();
        entity.setCageNo(request.getCageNo());
        entity.setSeaArea(request.getSeaArea());
        entity.setTimeZone(request.getTimeZone());
        entity.setRemark(request.getRemark());
        cageSeaAreaMapper.insert(entity);
        return entity;
    }

    /** 调整海区/时区：只影响之后登记的潮次与计算，历史潮次快照不变。 */
    @Transactional
    public CageSeaArea update(Long id, CageSeaAreaRequest request) {
        CageSeaArea entity = getById(id);
        TzUtils.requireZone(request.getTimeZone());
        entity.setSeaArea(request.getSeaArea());
        entity.setTimeZone(request.getTimeZone());
        entity.setRemark(request.getRemark());
        cageSeaAreaMapper.updateById(entity);
        return entity;
    }

    public CageSeaArea getById(Long id) {
        CageSeaArea entity = cageSeaAreaMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("网箱海区档案不存在: " + id);
        }
        return entity;
    }

    /** 按网箱号取档案；未登记时抛业务异常（计算/潮次登记的前置条件）。 */
    public CageSeaArea requireByCageNo(String cageNo) {
        CageSeaArea entity = findByCageNo(cageNo);
        if (entity == null) {
            throw new BusinessException("网箱未登记海区时区档案: " + cageNo);
        }
        return entity;
    }

    public CageSeaArea findByCageNo(String cageNo) {
        return cageSeaAreaMapper.selectOne(new LambdaQueryWrapper<CageSeaArea>()
                .eq(CageSeaArea::getCageNo, cageNo));
    }

    public List<CageSeaArea> list() {
        return cageSeaAreaMapper.selectList(new LambdaQueryWrapper<CageSeaArea>()
                .orderByAsc(CageSeaArea::getCageNo));
    }
}
