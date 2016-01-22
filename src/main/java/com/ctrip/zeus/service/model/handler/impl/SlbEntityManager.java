package com.ctrip.zeus.service.model.handler.impl;

import com.ctrip.zeus.dal.core.*;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.model.entity.Slb;
import com.ctrip.zeus.model.entity.VirtualServer;
import com.ctrip.zeus.model.transform.DefaultSaxParser;
import com.ctrip.zeus.service.model.IdVersion;
import com.ctrip.zeus.service.model.ModelMode;
import com.ctrip.zeus.service.model.VersionUtils;
import com.ctrip.zeus.service.model.handler.SlbSync;
import com.ctrip.zeus.support.C;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by zhoumy on 2015/9/29.
 */
@Component("slbEntityManager")
public class SlbEntityManager implements SlbSync {
    @Resource
    private SlbDao slbDao;
    @Resource
    private ArchiveSlbDao archiveSlbDao;
    @Resource
    private VirtualServerEntityManager virtualServerEntityManager;
    @Resource
    private SlbServerRelMaintainer slbServerRelMaintainer;
    @Resource
    private RSlbStatusDao rSlbStatusDao;
    @Resource
    private ConfSlbActiveDao confSlbActiveDao;

    @Override
    public void add(Slb slb) throws Exception {
        slb.setVersion(1);
        SlbDo d = C.toSlbDo(0L, slb);
        slbDao.insert(d);
        slb.setId(d.getId());
        for (VirtualServer virtualServer : slb.getVirtualServers()) {
            virtualServer.setSlbId(slb.getId());
            virtualServerEntityManager.add(virtualServer);
        }
        archiveSlbDao.insert(new ArchiveSlbDo().setSlbId(slb.getId()).setVersion(slb.getVersion())
                .setContent(ContentWriters.writeSlbContent(slb))
                .setHash(VersionUtils.getHash(slb.getId(), slb.getVersion())));
        rSlbStatusDao.insertOrUpdate(new RelSlbStatusDo().setSlbId(slb.getId()).setOfflineVersion(slb.getVersion()));
        slbServerRelMaintainer.addRel(slb, RelSlbSlbServerDo.class, slb.getSlbServers());
    }

    @Override
    public void update(Slb slb) throws Exception {
        RelSlbStatusDo check = rSlbStatusDao.findBySlb(slb.getId(), RSlbStatusEntity.READSET_FULL);
        if (check.getOfflineVersion() > slb.getVersion())
            throw new ValidationException("Newer Slb version is detected.");
        slb.setVersion(slb.getVersion() + 1);

        SlbDo d = C.toSlbDo(slb.getId(), slb);
        slbDao.updateById(d, SlbEntity.UPDATESET_FULL);
        archiveSlbDao.insert(new ArchiveSlbDo().setSlbId(slb.getId()).setVersion(slb.getVersion()).setContent(ContentWriters.writeSlbContent(slb)));
        rSlbStatusDao.insertOrUpdate(new RelSlbStatusDo().setId(slb.getId()).setOfflineVersion(slb.getVersion()));
        slbServerRelMaintainer.updateRel(slb, RelSlbSlbServerDo.class, slb.getSlbServers());
    }

    @Override
    public void updateStatus(IdVersion[] slbs) throws Exception {
        RelSlbStatusDo[] dos = new RelSlbStatusDo[slbs.length];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new RelSlbStatusDo().setSlbId(slbs[i].getId()).setOnlineVersion(slbs[i].getVersion());
        }
        rSlbStatusDao.updateOnlineVersionBySlb(dos, RSlbStatusEntity.UPDATESET_UPDATE_ONLINE_STATUS);
    }

    @Override
    public int delete(Long slbId) throws Exception {
        slbServerRelMaintainer.deleteRel(slbId);
        int count = slbDao.deleteByPK(new SlbDo().setId(slbId));
        archiveSlbDao.deleteBySlb(new ArchiveSlbDo().setSlbId(slbId));
        return count;
    }

    @Override
    public Set<Long> port(Long[] slbIds) throws Exception {
        List<Slb> toUpdate = new ArrayList<>();
        Set<Long> failed = new HashSet<>();
        for (ArchiveSlbDo archiveSlbDo : archiveSlbDao.findMaxVersionBySlbs(slbIds, ArchiveSlbEntity.READSET_FULL)) {
            try {
                toUpdate.add(DefaultSaxParser.parseEntity(Slb.class, archiveSlbDo.getContent()));
            } catch (Exception ex) {
                failed.add(archiveSlbDo.getId());
            }
        }
        RelSlbStatusDo[] dos = new RelSlbStatusDo[toUpdate.size()];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new RelSlbStatusDo().setSlbId(toUpdate.get(i).getId()).setOfflineVersion(toUpdate.get(i).getVersion());
        }

        rSlbStatusDao.insertOrUpdate(dos);

        for (Slb slb : toUpdate) {
            slbServerRelMaintainer.port(slb, RelSlbSlbServerDo.class, slb.getSlbServers());
        }

        slbIds = new Long[toUpdate.size()];
        for (int i = 0; i < slbIds.length; i++) {
            slbIds[i] = toUpdate.get(i).getId();
        }
        List<ConfSlbActiveDo> ref = confSlbActiveDao.findAllBySlbIds(slbIds, ConfSlbActiveEntity.READSET_FULL);
        toUpdate.clear();
        for (ConfSlbActiveDo confSlbActiveDo : ref) {
            try {
                toUpdate.add(DefaultSaxParser.parseEntity(Slb.class, confSlbActiveDo.getContent()));
            } catch (Exception ex) {
                failed.add(confSlbActiveDo.getSlbId());
            }
        }
        IdVersion[] keys = new IdVersion[toUpdate.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new IdVersion(toUpdate.get(i).getId(), toUpdate.get(i).getVersion());
        }

        updateStatus(keys);

        return failed;
    }
}
