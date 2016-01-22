package com.ctrip.zeus.service.model.handler.impl;

import com.ctrip.zeus.dal.core.*;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.model.entity.VirtualServer;
import com.ctrip.zeus.model.transform.DefaultSaxParser;
import com.ctrip.zeus.service.model.IdVersion;
import com.ctrip.zeus.service.model.VersionUtils;
import com.ctrip.zeus.service.model.handler.VirtualServerSync;
import com.ctrip.zeus.support.C;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;

/**
 * Created by zhoumy on 2015/9/22.
 */
@Component("virtualServerEntityManager")
public class VirtualServerEntityManager implements VirtualServerSync {
    @Resource
    private SlbVirtualServerDao slbVirtualServerDao;
    @Resource
    private VsDomainRelMaintainer vsDomainRelMaintainer;
    @Resource
    private RVsSlbDao rVsSlbDao;
    @Resource
    private ArchiveVsDao archiveVsDao;
    @Resource
    private RVsStatusDao rVsStatusDao;
    @Resource
    private ConfSlbVirtualServerActiveDao confSlbVirtualServerActiveDao;

    @Override
    public void add(VirtualServer virtualServer) throws Exception {
        virtualServer.setVersion(1);
        SlbVirtualServerDo d = C.toSlbVirtualServerDo(0L, virtualServer.getSlbId(), virtualServer);
        slbVirtualServerDao.insert(d);
        Long vsId = d.getId();
        virtualServer.setId(vsId);
        archiveVsDao.insert(new MetaVsArchiveDo().setVsId(vsId).setVersion(virtualServer.getVersion())
                .setContent(ContentWriters.writeVirtualServerContent(virtualServer))
                .setHash(VersionUtils.getHash(virtualServer.getId(), virtualServer.getVersion())));

        rVsStatusDao.insert(new RelVsStatusDo().setVsId(vsId).setOfflineVersion(virtualServer.getVersion()));
        rVsSlbDao.insert(new RelVsSlbDo().setVsId(vsId).setSlbId(virtualServer.getSlbId()).setVsVersion(virtualServer.getVersion())
                .setHash(VersionUtils.getHash(virtualServer.getId(), virtualServer.getVersion())));
        vsDomainRelMaintainer.addRel(virtualServer, RelVsDomainDo.class, virtualServer.getDomains());
    }

    @Override
    public void update(VirtualServer virtualServer) throws Exception {
        Long vsId = virtualServer.getId();
        RelVsStatusDo check = rVsStatusDao.findByVs(vsId, RVsStatusEntity.READSET_FULL);
        if (check.getOfflineVersion() > virtualServer.getVersion())
            throw new ValidationException("Newer virtual server version is detected.");
        virtualServer.setVersion(virtualServer.getVersion() + 1);

        SlbVirtualServerDo d = C.toSlbVirtualServerDo(vsId, virtualServer.getSlbId(), virtualServer);
        slbVirtualServerDao.updateByPK(d, SlbVirtualServerEntity.UPDATESET_FULL);
        archiveVsDao.insert(new MetaVsArchiveDo().setVsId(vsId).setContent(ContentWriters.writeVirtualServerContent(virtualServer))
                .setVersion(virtualServer.getVersion())
                .setHash(VersionUtils.getHash(virtualServer.getId(), virtualServer.getVersion())));

        rVsStatusDao.insertOrUpdate(new RelVsStatusDo().setVsId(vsId).setOfflineVersion(virtualServer.getVersion()));
        rVsSlbDao.insert(new RelVsSlbDo().setVsId(virtualServer.getId()).setSlbId(virtualServer.getSlbId()).setVsVersion(virtualServer.getVersion())
                .setHash(VersionUtils.getHash(virtualServer.getId(), virtualServer.getVersion())));
        vsDomainRelMaintainer.updateRel(virtualServer, RelVsDomainDo.class, virtualServer.getDomains());
    }

    @Override
    public void updateStatus(IdVersion[] virtualServers) throws Exception {
        RelVsStatusDo[] dos = new RelVsStatusDo[virtualServers.length];
        for (int i = 0; i < dos.length; i++) {
            dos[i] = new RelVsStatusDo().setVsId(virtualServers[i].getId()).setOnlineVersion(virtualServers[i].getVersion());
        }
        rVsStatusDao.updateOnlineVersionByVs(dos, RVsStatusEntity.UPDATESET_UPDATE_ONLINE_STATUS);
    }

    @Override
    public void delete(Long vsId) throws Exception {
        rVsSlbDao.deleteByVs(new RelVsSlbDo().setVsId(vsId));
        vsDomainRelMaintainer.deleteRel(vsId);
        rVsStatusDao.deleteAllByVs(new RelVsStatusDo().setVsId(vsId));
        slbVirtualServerDao.deleteById(new SlbVirtualServerDo().setId(vsId));
        archiveVsDao.deleteByVs(new MetaVsArchiveDo().setVsId(vsId));
    }

    @Override
    public Set<Long> port(Long[] vsIds) throws Exception {
        Map<Long, VirtualServer> toUpdate = new HashMap<>();
        Set<Long> failed = new HashSet<>();
        for (MetaVsArchiveDo metaVsArchiveDo : archiveVsDao.findMaxVersionByVses(vsIds, ArchiveVsEntity.READSET_FULL)) {
            try {
                VirtualServer vs = DefaultSaxParser.parseEntity(VirtualServer.class, metaVsArchiveDo.getContent());
                toUpdate.put(vs.getId(), vs);
            } catch (Exception ex) {
                failed.add(metaVsArchiveDo.getVsId());
            }
        }
        RelVsStatusDo[] rel1 = new RelVsStatusDo[toUpdate.size()];
        for (int i = 0; i < rel1.length; i++) {
            rel1[i] = new RelVsStatusDo().setVsId(toUpdate.get(i).getId()).setOfflineVersion(toUpdate.get(i).getVersion());
        }

        rVsStatusDao.insertOrUpdate(rel1);

        List<RelVsSlbDo> rel2 = rVsSlbDao.findByVses(vsIds, RVsSlbEntity.READSET_FULL);
        for (RelVsSlbDo d : rel2) {
            d.setHash(VersionUtils.getHash(d.getVsId(), toUpdate.get(d.getVsId()).getVersion()));
        }

        rVsSlbDao.update(rel2.toArray(new RelVsSlbDo[rel2.size()]), RVsSlbEntity.UPDATESET_FULL);
        for (VirtualServer virtualServer : toUpdate.values()) {
            vsDomainRelMaintainer.port(virtualServer, RelVsDomainDo.class, virtualServer.getDomains());
        }

        vsIds = new Long[toUpdate.size()];
        for (int i = 0; i < vsIds.length; i++) {
            vsIds[i] = toUpdate.get(i).getId();
        }
        List<ConfSlbVirtualServerActiveDo> ref = confSlbVirtualServerActiveDao.findBySlbVirtualServerIds(vsIds, ConfSlbVirtualServerActiveEntity.READSET_FULL);
        toUpdate.clear();
        for (ConfSlbVirtualServerActiveDo confSlbVirtualServerActiveDo : ref) {
            try {
                VirtualServer vs = DefaultSaxParser.parseEntity(VirtualServer.class, confSlbVirtualServerActiveDo.getContent());
                toUpdate.put(vs.getId(), vs);
            } catch (Exception ex) {
                failed.add(confSlbVirtualServerActiveDo.getSlbVirtualServerId());
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
