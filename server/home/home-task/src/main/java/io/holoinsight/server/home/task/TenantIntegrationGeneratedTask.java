/*
 * Copyright 2022 Holoinsight Project Authors. Licensed under Apache-2.0.
 */
package io.holoinsight.server.home.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.gson.reflect.TypeToken;
import io.holoinsight.server.common.J;
import io.holoinsight.server.common.MD5Hash;
import io.holoinsight.server.common.dao.entity.TenantOps;
import io.holoinsight.server.home.biz.common.MetaDictUtil;
import io.holoinsight.server.home.biz.plugin.PluginRepository;
import io.holoinsight.server.home.biz.plugin.core.AbstractIntegrationPlugin;
import io.holoinsight.server.home.biz.plugin.model.Plugin;
import io.holoinsight.server.home.biz.plugin.model.PluginType;
import io.holoinsight.server.home.biz.service.IntegrationGeneratedService;
import io.holoinsight.server.home.biz.service.IntegrationPluginService;
import io.holoinsight.server.home.biz.service.MetaService;
import io.holoinsight.server.home.biz.service.MetaService.AppModel;
import io.holoinsight.server.home.biz.service.TenantInitService;
import io.holoinsight.server.home.biz.service.TenantOpsService;
import io.holoinsight.server.home.common.model.TaskEnum;
import io.holoinsight.server.home.common.util.cache.local.CommonLocalCache;
import io.holoinsight.server.home.dal.model.IntegrationGenerated;
import io.holoinsight.server.home.dal.model.dto.CloudMonitorRange;
import io.holoinsight.server.home.dal.model.dto.GaeaCollectConfigDTO.GaeaCollectRange;
import io.holoinsight.server.home.dal.model.dto.IntegrationGeneratedDTO;
import io.holoinsight.server.home.dal.model.dto.IntegrationPluginDTO;
import io.holoinsight.server.meta.common.model.QueryExample;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import io.holoinsight.server.meta.facade.service.DataClientService;

import static io.holoinsight.server.home.biz.common.MetaDictKey.INTEGRATION_LOCAL_PRODUCT;
import static io.holoinsight.server.home.biz.common.MetaDictType.INTEGRATION_CONFIG;
import static io.holoinsight.server.home.common.util.cache.local.CacheConst.APP_META_KEY;
import static io.holoinsight.server.home.common.util.cache.local.CacheConst.INTEGRATION_GENERATED_CACHE_KEY;

/**
 *
 * @author jsy1001de
 * @version 1.0: TenantIntegrationGeneratedTask.java, v 0.1 2022年06月30日 4:51 下午 jsy1001de Exp $
 */
@Slf4j
@Service
@TaskHandler(TaskEnum.TENANT_INTEGRATION_GENERATED)
public class TenantIntegrationGeneratedTask extends AbstractMonitorTask {

  @Autowired
  private DataClientService dataClientService;

  @Autowired
  private IntegrationPluginService integrationPluginService;

  @Autowired
  private IntegrationGeneratedService integrationGeneratedService;

  @Autowired
  private PluginRepository pluginRepository;

  @Autowired
  private TenantOpsService tenantOpsService;

  @Autowired
  private TenantInitService tenantInitService;

  @Autowired
  private MetaService metaService;

  public TenantIntegrationGeneratedTask() {
    super(1, 2, TaskEnum.TENANT_INTEGRATION_GENERATED);
  }

  @Override
  public boolean needRun() {
    return true;
  }

  public long getTaskPeriod() {
    return FIVE_MINUTE;
  }

  @Override
  public List<MonitorTaskJob> buildJobs(long period) throws Throwable {
    List<MonitorTaskJob> jobs = new ArrayList<>();

    jobs.add(new MonitorTaskJob() {
      @Override
      public boolean run() throws Throwable {

        syncAoAction();
        return true;
      }

      @Override
      public String id() {
        return "TenantIntegrationGeneratedTask";
      }
    });
    return jobs;
  }

  private void syncAoAction() {

    Map<String, Long> fromDb = getFromDb();
    Set<String> fromDbKeys = fromDb.keySet();
    log.info("fromDb: " + fromDbKeys.size());
    Map<String, IntegrationGeneratedDTO> outMaps = getIntegrationGeneratedList();
    log.info("outMaps: " + outMaps.size());
    List<IntegrationGeneratedDTO> newList = new ArrayList<>();
    for (String outKeys : outMaps.keySet()) {
      if (fromDbKeys.contains(outKeys)) {
        fromDbKeys.remove(outKeys);
      } else {
        newList.add(outMaps.get(outKeys));
      }
    }

    // create
    if (!CollectionUtils.isEmpty(newList)) {
      log.info("newList: " + newList.size());
      for (IntegrationGeneratedDTO integrationGeneratedDTO : newList) {
        integrationGeneratedService.insert(integrationGeneratedDTO);
      }
    }

    // delete
    if (!CollectionUtils.isEmpty(fromDbKeys)) {
      log.info("deleteList: " + fromDbKeys.size());
      for (String dbkey : fromDbKeys) {
        IntegrationGenerated integrationGenerated = new IntegrationGenerated();
        integrationGenerated.setDeleted(true);
        integrationGenerated.setId(fromDb.get(dbkey));
        integrationGeneratedService.updateById(integrationGenerated);
      }
    }
  }

  private Map<String, Long> getFromDb() {
    List<TenantOps> tenantOps = tenantOpsService.list();
    Map<String, Long> dbUks = new HashMap<>();

    for (TenantOps ops : tenantOps) {
      List<IntegrationGenerated> dblist =
          integrationGeneratedService.queryByTenant(ops.getTenant());
      if (CollectionUtils.isEmpty(dblist)) {
        continue;
      }

      for (IntegrationGenerated generated : dblist) {
        dbUks.put(
            String.format("%s_%s_%s_%s_%s", generated.getTenant(), generated.getWorkspace(),
                generated.getProduct(), generated.getItem(), generated.getName()),
            generated.getId());
      }
    }

    return dbUks;
  }

  private Map<String, IntegrationGeneratedDTO> getIntegrationGeneratedList() {

    Map<String, IntegrationGeneratedDTO> dtoMap = new HashMap<>();
    List<IntegrationGeneratedDTO> generateds = new ArrayList<>();
    List<TenantOps> tenantOps = tenantOpsService.list();
    for (TenantOps ops : tenantOps) {

      String tableName = ops.getTenant() + "_app";

      List<AppModel> dbApps = getDbApps(tableName);
      if (CollectionUtils.isEmpty(dbApps))
        continue;

      for (AppModel appModel : dbApps) {
        if (appModel.getMachineType().equalsIgnoreCase("VM")) {
          generateds.add(integrationGeneratedService.generated(ops.getTenant(),
              appModel.getWorkspace(), appModel.getApp(), "vmsystem", "System", new HashMap<>()));
        } else {
          generateds.add(integrationGeneratedService.generated(ops.getTenant(),
              appModel.getWorkspace(), appModel.getApp(), "podsystem", "System", new HashMap<>()));
        }
        generateds.add(integrationGeneratedService.generated(ops.getTenant(),
            appModel.getWorkspace(), appModel.getApp(), "portcheck", "PortCheck", new HashMap<>()));
        generateds
            .add(integrationGeneratedService.generated(ops.getTenant(), appModel.getWorkspace(),
                appModel.getApp(), "logpattern", "LogPattern", new HashMap<>()));

        Map<String, String> dictMap = MetaDictUtil.getValue(INTEGRATION_CONFIG,
            INTEGRATION_LOCAL_PRODUCT, new TypeToken<Map<String, String>>() {});
        if (CollectionUtils.isEmpty(dictMap))
          continue;
        for (Map.Entry<String, String> dict : dictMap.entrySet()) {
          generateds
              .add(integrationGeneratedService.generated(ops.getTenant(), appModel.getWorkspace(),
                  appModel.getApp(), dict.getValue(), dict.getKey(), new HashMap<>()));
        }
      }

      List<IntegrationPluginDTO> integrationPluginDTOS =
          integrationPluginService.queryByTenant(ops.getTenant());

      if (CollectionUtils.isEmpty(integrationPluginDTOS))
        continue;

      for (IntegrationPluginDTO integrationPluginDTO : integrationPluginDTOS) {
        Plugin plugin = this.pluginRepository.getTemplate(integrationPluginDTO.type,
            integrationPluginDTO.version);
        if (null == plugin || plugin.getPluginType().equals(PluginType.hosting)) {
          continue;
        }

        AbstractIntegrationPlugin abstractIntegrationPlugin = (AbstractIntegrationPlugin) plugin;
        List<AbstractIntegrationPlugin> abstractIntegrationPlugins =
            abstractIntegrationPlugin.genPluginList(integrationPluginDTO);
        if (CollectionUtils.isEmpty(abstractIntegrationPlugins))
          continue;

        Set<String> appItemSets = new HashSet<>();
        for (AbstractIntegrationPlugin integrationPlugin : abstractIntegrationPlugins) {
          GaeaCollectRange gaeaCollectRange = integrationPlugin.getGaeaCollectRange();

          if (gaeaCollectRange.getType().equalsIgnoreCase("central")
              || null == gaeaCollectRange.getCloudmonitor()
              || StringUtils.isBlank(gaeaCollectRange.getCloudmonitor().table))
            continue;

          Set<String> collectApps = getCollectApps(gaeaCollectRange.getCloudmonitor());

          if (CollectionUtils.isEmpty(collectApps))
            continue;

          for (String app : collectApps) {
            if (StringUtils.isBlank(integrationPlugin.name))
              continue;
            String uk = app + "#" + integrationPlugin.name;
            if (appItemSets.contains(uk))
              continue;

            generateds.add(integrationGeneratedService.generated(ops.getTenant(),
                integrationPluginDTO.getWorkspace(), app, integrationPlugin.name,
                integrationPluginDTO.getProduct(), new HashMap<>()));

            appItemSets.add(uk);
          }
        }
        log.info("outList: " + tableName + ", generated size: " + generateds.size());
      }
    }

    List<IntegrationGeneratedDTO> extraGeneratedLists = tenantInitService.getExtraGeneratedLists();
    if (!CollectionUtils.isEmpty(extraGeneratedLists)) {
      generateds.addAll(extraGeneratedLists);
    }

    for (IntegrationGeneratedDTO generated : generateds) {
      if (!tenantInitService.checkIntegrationWorkspace(generated.workspace)) {
        continue;
      }
      dtoMap.put(String.format("%s_%s_%s_%s_%s", generated.getTenant(), generated.getWorkspace(),
          generated.getProduct(), generated.getItem(), generated.getName()), generated);
    }
    return dtoMap;
  }


  private Set<String> getCollectApps(CloudMonitorRange cloudMonitorRange) {

    String cacheKey =
        INTEGRATION_GENERATED_CACHE_KEY + "@" + MD5Hash.getMD5(J.toJson(cloudMonitorRange));
    Set<String> o = CommonLocalCache.get(cacheKey);

    if (!CollectionUtils.isEmpty(o)) {
      return o;
    }

    List<Map<String, Object>> mapList = new ArrayList<>();
    if (CollectionUtils.isEmpty(cloudMonitorRange.condition)) {
      List<Map<String, Object>> metaList = dataClientService.queryAll(cloudMonitorRange.getTable());
      if (CollectionUtils.isEmpty(metaList))
        return new HashSet<>();

      mapList.addAll(metaList);

    } else {
      QueryExample queryExample = new QueryExample();
      queryExample.getParams().putAll(cloudMonitorRange.getCondition().get(0));
      List<Map<String, Object>> metaList =
          dataClientService.queryByExample(cloudMonitorRange.getTable(), queryExample);
      if (CollectionUtils.isEmpty(metaList))
        return new HashSet<>();
      mapList.addAll(metaList);
    }
    Set<String> appSets = new HashSet<>();

    for (Map<String, Object> map : mapList) {
      if (!map.containsKey("app")) {
        continue;
      }

      String app = map.get("app").toString();
      appSets.add(app);
    }

    CommonLocalCache.put(cacheKey, appSets, 10, TimeUnit.MINUTES);

    return appSets;
  }

  private List<AppModel> getDbApps(String tableName) {

    String cacheKey = APP_META_KEY + "@" + tableName;
    Object o = CommonLocalCache.get(cacheKey);
    if (null != o) {
      return (List<AppModel>) o;
    }

    List<AppModel> appModels = metaService.getAppModelFromAppTable(tableName);

    if (CollectionUtils.isEmpty(appModels))
      return new ArrayList<>();

    CommonLocalCache.put(cacheKey, appModels, 10, TimeUnit.MINUTES);

    return appModels;
  }
}
