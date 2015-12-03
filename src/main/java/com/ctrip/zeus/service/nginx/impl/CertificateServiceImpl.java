package com.ctrip.zeus.service.nginx.impl;

import com.ctrip.zeus.client.AbstractRestClient;
import com.ctrip.zeus.dal.core.*;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.service.nginx.CertificateConfig;
import com.ctrip.zeus.service.nginx.CertificateService;
import com.ctrip.zeus.util.IOUtils;
import com.google.common.base.Joiner;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.*;

/**
 * Created by zhoumy on 2015/10/29.
 */
@Service("certificateService")
public class CertificateServiceImpl implements CertificateService {
    @Resource
    private CertificateDao certificateDao;
    @Resource
    private RCertificateSlbServerDao rCertificateSlbServerDao;

    @Override
    public Long getCertificateOnBoard(String[] domains) throws Exception {
        CertificateDo value;
        String[] searchRange = getDomainSearchRange(domains);
        String domainValue;
        boolean state = CertificateConfig.ONBOARD;
        if (searchRange.length == 0)
            throw new ValidationException("Domain info is not found when searching certificate.");
        if (searchRange.length == 1) {
            domainValue = searchRange[0];
            value = certificateDao.findMaxByDomainAndState(domainValue, state, CertificateEntity.READSET_FULL);
        } else {
            List<CertificateDo> check = certificateDao.grossByDomainAndState(searchRange, state, CertificateEntity.READSET_FULL);
            if (check.isEmpty())
                throw new ValidationException("Cannot find corresponding certificate.");
            if (check.size() > 1) {
                throw new ValidationException("Multiple certificates found referring the domain list.");
            }
            domainValue = check.get(0).getDomain();
            value = certificateDao.findMaxByDomainAndState(domainValue, state, CertificateEntity.READSET_FULL);
        }
        if (value == null)
            throw new ValidationException("Cannot find corresponding certificate referring domain " + domainValue + ".");
        return value.getId();
    }

    @Override
    public Long upload(InputStream cert, InputStream key, String domain, boolean state) throws Exception {
        if (cert == null || key == null)
            throw new ValidationException("Cert or key file is null.");
        CertificateDo max = certificateDao.findMaxByDomainAndState(domain, state, CertificateEntity.READSET_FULL);
        if (max != null)
            throw new ValidationException("Certificate exists.");
        CertificateDo d = new CertificateDo()
                .setCert(IOUtils.getBytes(cert)).setKey(IOUtils.getBytes(key)).setDomain(domain).setState(state).setVersion(1);
        certificateDao.insert(d);
        return d.getId();
    }

    @Override
    public Long upgrade(InputStream cert, InputStream key, String domain, boolean state) throws Exception {
        if (cert == null || key == null)
            throw new ValidationException("Cert or key file is null.");
        CertificateDo max = certificateDao.findMaxByDomainAndState(domain, state, CertificateEntity.READSET_FULL);
        if (max == null)
            throw new ValidationException("No history has found. No need to upgrade.");
        CertificateDo d = new CertificateDo()
                .setCert(IOUtils.getBytes(cert)).setKey(IOUtils.getBytes(key)).setDomain(domain).setState(state).setVersion(max.getVersion() + 1);
        certificateDao.insert(d);
        return d.getId();
    }

    @Override
    public void install(Long vsId, List<String> ips, Long certId) throws Exception {
        List<RelCertSlbServerDo> dos = rCertificateSlbServerDao.findByVs(vsId, RCertificateSlbServerEntity.READSET_FULL);
        Set<String> check = new HashSet<>();
        for (RelCertSlbServerDo d : dos) {
            check.add(d.getIp() + "#" + vsId + "#" + d.getCertId());
        }
        boolean success = true;
        String errMsg = "";
        for (String ip : ips) {
            if (check.contains(ip + "#" + vsId + "#" + certId))
                continue;
            CertSyncClient c = new CertSyncClient("http://" + ip + ":8099");
            Response res = c.requestInstall(vsId, certId);
            // retry
            if (res.getStatus() / 100 > 2)
                res = c.requestInstall(vsId, certId);
            // still failed after retry
            if (res.getStatus() / 100 > 2) {
                success &= false;
                try {
                    errMsg += ip + ":" + IOUtils.inputStreamStringify((InputStream) res.getEntity()) + "\n";
                } catch (IOException e) {
                    errMsg += ip + ":" + "Unable to parse the response entity.\n";
                }
            }
            if (!success)
                throw new Exception(errMsg);
        }
    }

    @Override
    public void uninstallIfRecalled(Long vsId, List<String> ips) throws Exception {
        Map<String, RelCertSlbServerDo> abandoned = new HashMap<>();
        for (RelCertSlbServerDo d : rCertificateSlbServerDao.findByVs(vsId, RCertificateSlbServerEntity.READSET_FULL)) {
            if (ips.contains(d.getIp()))
                abandoned.put(d.getIp(), d);
        }
        boolean success = true;
        String errMsg = "";
        for (Map.Entry<String, RelCertSlbServerDo> entry : abandoned.entrySet()) {
            boolean result = true;
            CertSyncClient c = new CertSyncClient("http://" + entry.getKey() + ":8099");
            Response res = c.requestUninstall(vsId);
            // retry
            if (res.getStatus() / 100 > 2)
                res = c.requestUninstall(vsId);
            // still failed after retry
            if (res.getStatus() / 100 > 2) {
                result &= false;
                try {
                    errMsg += entry.getKey() + ":" + IOUtils.inputStreamStringify((InputStream) res.getEntity()) + "\n";
                } catch (IOException e) {
                    errMsg += entry.getKey() + ":" + "Unable to parse the response entity.\n";
                }
            }
            if (result)
                rCertificateSlbServerDao.deleteAllById(entry.getValue());
            success &= result;
        }
        if (!success)
            throw new Exception(errMsg);
    }

    private String[] getDomainSearchRange(String[] domains) {
        if (domains.length <= 1)
            return domains;
        else {
            Arrays.sort(domains);
//            String[] values = new String[domains.length + 1];
            String[] values = new String[1];
            values[0] = Joiner.on("|").join(domains);
//            for (int i = 1; i < values.length; i++) {
//                values[i] = domains[i - 1];
//            }
            return values;
        }
    }

    private static class CertSyncClient extends AbstractRestClient {
        protected CertSyncClient(String url) {
            super(url);
        }

        public Response requestInstall(Long vsId, Long certId) throws ValidationException {
            return getTarget().path("/api/op/installcerts").queryParam("vsId", vsId).queryParam("certId", certId).request().get();
        }

        public Response requestUninstall(Long vsId) throws ValidationException {
            return getTarget().path("/api/op/uninstallcerts").queryParam("vsId", vsId).request().get();
        }
    }
}
