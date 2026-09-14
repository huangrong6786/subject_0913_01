package com.evops.aquaculture.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.aquaculture.dto.TideSessionCreateRequest;
import com.evops.aquaculture.entity.CageSeaArea;
import com.evops.aquaculture.entity.TideSession;
import com.evops.aquaculture.mapper.TideSessionMapper;
import com.evops.common.BusinessException;
import com.evops.common.util.TzUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 潮次登记：潮汐开始/结束时刻一律左闭右开 [startAtUtc, endAtUtc)。
 * 跨午夜潮次按网箱所在海区时区归属业务日（businessDate = 开始时刻在海区时区下的当地日期）；
 * 登记时快照网箱海区档案，档案后续调整不影响已登记潮次的归属。
 */
@Service
public class TideSessionService {

    private final TideSessionMapper tideSessionMapper;
    private final CageSeaAreaService cageSeaAreaService;

    public TideSessionService(TideSessionMapper tideSessionMapper,
                              CageSeaAreaService cageSeaAreaService) {
        this.tideSessionMapper = tideSessionMapper;
        this.cageSeaAreaService = cageSeaAreaService;
    }

    /** 登记潮次：校验左闭右开、同网箱潮次不重叠，并按海区时区计算归属业务日。 */
    @Transactional
    public TideSession create(TideSessionCreateRequest request) {
        TideSession existing = tideSessionMapper.selectOne(new LambdaQueryWrapper<TideSession>()
                .eq(TideSession::getTideNo, request.getTideNo()));
        if (existing != null) {
            throw new BusinessException("潮次编号已存在: " + request.getTideNo());
        }
        if (!request.getStartAtUtc().isBefore(request.getEndAtUtc())) {
            throw new BusinessException("潮汐开始时刻必须早于结束时刻（左闭右开区间）: " + request.getTideNo());
        }
        CageSeaArea seaArea = cageSeaAreaService.requireByCageNo(request.getCageNo());
        ZoneId zoneId = TzUtils.requireZone(seaArea.getTimeZone());

        // 同网箱潮次区间不得重叠（左闭右开相交判定）
        List<TideSession> cageTides = tideSessionMapper.selectList(new LambdaQueryWrapper<TideSession>()
                .eq(TideSession::getCageNo, request.getCageNo())
                .lt(TideSession::getStartAtUtc, request.getEndAtUtc())
                .gt(TideSession::getEndAtUtc, request.getStartAtUtc()));
        if (!cageTides.isEmpty()) {
            TideSession conflict = cageTides.get(0);
            throw new BusinessException("潮次时间区间重叠，拒绝登记: " + request.getTideNo()
                    + " 与既有潮次 " + conflict.getTideNo() + " 存在重叠");
        }

        TideSession entity = new TideSession();
        entity.setTideNo(request.getTideNo());
        entity.setCageNo(request.getCageNo());
        entity.setSeaArea(seaArea.getSeaArea());
        entity.setTimeZone(seaArea.getTimeZone());
        entity.setStartAtUtc(request.getStartAtUtc());
        entity.setEndAtUtc(request.getEndAtUtc());
        // 跨午夜归属：以开始时刻在海区时区下的当地日期为业务日
        LocalDate businessDate = TzUtils.toLocal(request.getStartAtUtc(), zoneId).toLocalDate();
        entity.setBusinessDate(businessDate);
        tideSessionMapper.insert(entity);
        return entity;
    }

    public TideSession getById(Long id) {
        TideSession entity = tideSessionMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("潮次不存在: " + id);
        }
        return entity;
    }

    public List<TideSession> list(String cageNo, LocalDate businessDate) {
        return tideSessionMapper.selectList(new LambdaQueryWrapper<TideSession>()
                .eq(cageNo != null && !cageNo.isEmpty(), TideSession::getCageNo, cageNo)
                .eq(businessDate != null, TideSession::getBusinessDate, businessDate)
                .orderByAsc(TideSession::getStartAtUtc));
    }
}
