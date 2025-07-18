/*
 * Copyright 2024 Apollo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.ctrip.framework.apollo.portal.controller;

import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ItemDTO;
import com.ctrip.framework.apollo.common.dto.NamespaceDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.core.enums.ConfigFileFormat;
import com.ctrip.framework.apollo.portal.environment.Env;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.ctrip.framework.apollo.portal.component.UserPermissionValidator;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceSyncModel;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceTextModel;
import com.ctrip.framework.apollo.portal.entity.vo.ItemDiffs;
import com.ctrip.framework.apollo.portal.entity.vo.NamespaceIdentifier;
import com.ctrip.framework.apollo.portal.service.ItemService;
import com.ctrip.framework.apollo.portal.service.NamespaceService;
import com.ctrip.framework.apollo.portal.spi.UserInfoHolder;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

@RestController
public class ItemController {

  private final ItemService configService;
  private final NamespaceService namespaceService;
  private final UserInfoHolder userInfoHolder;
  private final UserPermissionValidator userPermissionValidator;

  public ItemController(final ItemService configService, final UserInfoHolder userInfoHolder,
                        final UserPermissionValidator userPermissionValidator, final NamespaceService namespaceService) {
    this.configService = configService;
    this.userInfoHolder = userInfoHolder;
    this.userPermissionValidator = userPermissionValidator;
    this.namespaceService = namespaceService;
  }

  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @PutMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/items", consumes = {
      "application/json"})
  public void modifyItemsByText(@PathVariable String appId, @PathVariable String env,
                                @PathVariable String clusterName, @PathVariable String namespaceName,
                                @RequestBody NamespaceTextModel model) {
    model.setAppId(appId);
    model.setClusterName(clusterName);
    model.setEnv(env);
    model.setNamespaceName(namespaceName);

    configService.updateConfigItemByText(model);
  }

  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @PostMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/item")
  public ItemDTO createItem(@PathVariable String appId, @PathVariable String env,
                            @PathVariable String clusterName, @PathVariable String namespaceName,
                            @RequestBody ItemDTO item) {
    checkModel(isValidItem(item));

    //protect
    item.setLineNum(0);
    item.setId(0);
    String userId = userInfoHolder.getUser().getUserId();
    item.setDataChangeCreatedBy(userId);
    item.setDataChangeLastModifiedBy(userId);
    item.setDataChangeCreatedTime(null);
    item.setDataChangeLastModifiedTime(null);

    return configService.createItem(appId, Env.valueOf(env), clusterName, namespaceName, item);
  }

  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @PutMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/item")
  public void updateItem(@PathVariable String appId, @PathVariable String env,
                         @PathVariable String clusterName, @PathVariable String namespaceName,
                         @RequestBody ItemDTO item) {
    checkModel(isValidItem(item));

    String username = userInfoHolder.getUser().getUserId();
    item.setDataChangeLastModifiedBy(username);

    configService.updateItem(appId, Env.valueOf(env), clusterName, namespaceName, item);
  }


  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @DeleteMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/items/{itemId}")
  public void deleteItem(@PathVariable String appId, @PathVariable String env,
                         @PathVariable String clusterName, @PathVariable String namespaceName,
                         @PathVariable long itemId) {
    ItemDTO item = configService.loadItemById(Env.valueOf(env), itemId);
    NamespaceDTO namespace = namespaceService.loadNamespaceBaseInfo(appId, Env.valueOf(env), clusterName, namespaceName);

    // In case someone constructs an attack scenario
    if (namespace == null || item.getNamespaceId() != namespace.getId()) {
      throw BadRequestException.namespaceNotMatch();
    }

    configService.deleteItem(Env.valueOf(env), itemId, userInfoHolder.getUser().getUserId());
  }


  @GetMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/items")
  public List<ItemDTO> findItems(@PathVariable String appId, @PathVariable String env,
                                 @PathVariable String clusterName, @PathVariable String namespaceName,
                                 @RequestParam(defaultValue = "lineNum") String orderBy) {

    if (userPermissionValidator.shouldHideConfigToCurrentUser(appId, env, clusterName, namespaceName)) {
      return Collections.emptyList();
    }

    List<ItemDTO> items = configService.findItems(appId, Env.valueOf(env), clusterName, namespaceName);
    if ("lastModifiedTime".equals(orderBy)) {
      items.sort((o1, o2) -> {
        if (o1.getDataChangeLastModifiedTime().after(o2.getDataChangeLastModifiedTime())) {
          return -1;
        }
        if (o1.getDataChangeLastModifiedTime().before(o2.getDataChangeLastModifiedTime())) {
          return 1;
        }
        return 0;
      });
    }
    return items;
  }

  @GetMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/items")
  public List<ItemDTO> findBranchItems(@PathVariable("appId") String appId, @PathVariable String env,
                                       @PathVariable("clusterName") String clusterName,
                                       @PathVariable("namespaceName") String namespaceName,
                                       @PathVariable("branchName") String branchName) {

    return findItems(appId, env, branchName, namespaceName, "lastModifiedTime");
  }

  @PostMapping(value = "/namespaces/{namespaceName}/diff", consumes = {"application/json"})
  public List<ItemDiffs> diff(@RequestBody NamespaceSyncModel model) {
    checkModel(!model.isInvalid());

    List<ItemDiffs> itemDiffs = configService.compare(model.getSyncToNamespaces(), model.getSyncItems());

    for (ItemDiffs diff : itemDiffs) {
      NamespaceIdentifier namespace = diff.getNamespace();
      if (namespace == null) {
        continue;
      }

      if (userPermissionValidator
          .shouldHideConfigToCurrentUser(namespace.getAppId(), namespace.getEnv().getName(),
              namespace.getClusterName(), namespace.getNamespaceName())) {
        diff.setDiffs(new ItemChangeSets());
        diff.setExtInfo("You are not this project's administrator, nor you have edit or release permission for the namespace: " + namespace);
      }
    }

    return itemDiffs;
  }

  @PutMapping(value = "/apps/{appId}/namespaces/{namespaceName}/items", consumes = {"application/json"})
  public ResponseEntity<Void> update(@PathVariable String appId, @PathVariable String namespaceName,
                                     @RequestBody NamespaceSyncModel model) {
    checkModel(!model.isInvalid() && model.syncToNamespacesValid(appId, namespaceName));
    NamespaceIdentifier noPermissionNamespace = null;
    // check if user has every namespace's ModifyNamespace permission
    boolean hasPermission = true;
    for (NamespaceIdentifier namespaceIdentifier : model.getSyncToNamespaces()) {
      // once user has not one of the namespace's ModifyNamespace permission, then break the loop
      hasPermission = userPermissionValidator.hasModifyNamespacePermission(
          namespaceIdentifier.getAppId(),
          namespaceIdentifier.getEnv().getName(),
          namespaceIdentifier.getClusterName(),
          namespaceIdentifier.getNamespaceName()
      );
      if (!hasPermission) {
        noPermissionNamespace = namespaceIdentifier;
        break;
      }
    }
    if (hasPermission) {
      configService.syncItems(model.getSyncToNamespaces(), model.getSyncItems());
      return ResponseEntity.status(HttpStatus.OK).build();
    }
    throw new AccessDeniedException(String.format("You don't have the permission to modify namespace: %s", noPermissionNamespace));
  }

  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @PostMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/syntax-check", consumes = {
      "application/json"})
  public ResponseEntity<Void> syntaxCheckText(@PathVariable String appId, @PathVariable String env,
      @PathVariable String clusterName, @PathVariable String namespaceName, @RequestBody NamespaceTextModel model) {

    doSyntaxCheck(model);

    return ResponseEntity.ok().build();
  }

  @PreAuthorize(value = "@userPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
  @PutMapping("/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/revoke-items")
  public void revokeItems(@PathVariable String appId, @PathVariable String env, @PathVariable String clusterName,
      @PathVariable String namespaceName) {
    configService.revokeItem(appId, Env.valueOf(env), clusterName, namespaceName);
  }

  void doSyntaxCheck(NamespaceTextModel model) {
    if (StringUtils.isBlank(model.getConfigText())) {
      return;
    }

    // only support yaml syntax check
    if (model.getFormat() != ConfigFileFormat.YAML && model.getFormat() != ConfigFileFormat.YML) {
      return;
    }

    // use YamlPropertiesFactoryBean to check the yaml syntax
    TypeLimitedYamlPropertiesFactoryBean yamlPropertiesFactoryBean = new TypeLimitedYamlPropertiesFactoryBean();
    yamlPropertiesFactoryBean.setResources(new ByteArrayResource(model.getConfigText().getBytes()));
    try {
      // this call converts yaml to properties and will throw exception if the conversion fails
      yamlPropertiesFactoryBean.getObject();
    }catch (Exception ex){
      throw new BadRequestException(ex.getMessage());
    }
  }

  private boolean isValidItem(ItemDTO item) {
    return Objects.nonNull(item) && !StringUtils.isContainEmpty(item.getKey());
  }

  private static class TypeLimitedYamlPropertiesFactoryBean extends YamlPropertiesFactoryBean {
    @Override
    protected Yaml createYaml() {
      LoaderOptions loaderOptions = new LoaderOptions();
      loaderOptions.setAllowDuplicateKeys(false);
      DumperOptions dumperOptions = new DumperOptions();
      return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }
  }

}
