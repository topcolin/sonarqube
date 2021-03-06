/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.component.ws;

import com.google.common.base.Throwables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Date;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;
import org.sonar.api.i18n.I18n;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbClient;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.component.ResourceTypesRule;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonar.test.JsonAssert;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsComponents.TreeWsResponse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.db.component.ComponentTesting.newDevProjectCopy;
import static org.sonar.db.component.ComponentTesting.newDeveloper;
import static org.sonar.db.component.ComponentTesting.newDirectory;
import static org.sonar.db.component.ComponentTesting.newModuleDto;
import static org.sonar.db.component.ComponentTesting.newProjectCopy;
import static org.sonar.db.component.ComponentTesting.newProjectDto;
import static org.sonar.db.component.ComponentTesting.newSubView;
import static org.sonar.db.component.ComponentTesting.newView;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_COMPONENT_ID;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_QUALIFIERS;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_STRATEGY;

public class TreeActionTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);

  private ResourceTypesRule resourceTypes = new ResourceTypesRule();
  private ComponentDbTester componentDb = new ComponentDbTester(db);
  private DbClient dbClient = db.getDbClient();

  private WsActionTester ws;

  @Before
  public void setUp() {
    ws = new WsActionTester(new TreeAction(dbClient, new ComponentFinder(dbClient), resourceTypes, userSession, Mockito.mock(I18n.class)));
    resourceTypes.setChildrenQualifiers(Qualifiers.MODULE, Qualifiers.FILE, Qualifiers.DIRECTORY);
    resourceTypes.setLeavesQualifiers(Qualifiers.FILE, Qualifiers.UNIT_TEST_FILE);
  }

  @Test
  public void json_example() throws IOException {
    ComponentDto project = initJsonExampleComponents();
    logInWithBrowsePermission(project);

    String response = ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, project.uuid())
      .execute().getInput();

    JsonAssert.assertJson(response)
      .withStrictArrayOrder()
      .isSimilarTo(getClass().getResource("tree-example.json"));
  }

  @Test
  public void return_children() throws IOException {
    ComponentDto project = newProjectDto(db.organizations().insert(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    ComponentDto module = newModuleDto("module-uuid-1", project);
    componentDb.insertComponent(module);
    componentDb.insertComponent(newFileDto(project, 1));
    for (int i = 2; i <= 9; i++) {
      componentDb.insertComponent(newFileDto(module, i));
    }
    ComponentDto directory = newDirectory(module, "directory-path-1");
    componentDb.insertComponent(directory);
    componentDb.insertComponent(newFileDto(module, directory, 10));
    db.commit();
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "children")
      .setParam(PARAM_COMPONENT_ID, "module-uuid-1")
      .setParam(Param.PAGE, "2")
      .setParam(Param.PAGE_SIZE, "3")
      .setParam(Param.TEXT_QUERY, "file-name")
      .setParam(Param.ASCENDING, "false")
      .setParam(Param.SORT, "name"));

    assertThat(response.getComponentsCount()).isEqualTo(3);
    assertThat(response.getPaging().getTotal()).isEqualTo(8);
    assertThat(response.getComponentsList()).extracting("id").containsExactly("file-uuid-6", "file-uuid-5", "file-uuid-4");
  }

  @Test
  public void return_descendants() throws IOException {
    ComponentDto project = newProjectDto(db.getDefaultOrganization(), "project-uuid");
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(project);
    ComponentDto module = newModuleDto("module-uuid-1", project);
    componentDb.insertComponent(module);
    componentDb.insertComponent(newFileDto(project, 10));
    for (int i = 2; i <= 9; i++) {
      componentDb.insertComponent(newFileDto(module, i));
    }
    ComponentDto directory = newDirectory(module, "directory-path-1");
    componentDb.insertComponent(directory);
    componentDb.insertComponent(newFileDto(module, directory, 1));
    db.commit();
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "all")
      .setParam(PARAM_COMPONENT_ID, "module-uuid-1")
      .setParam(Param.PAGE, "2")
      .setParam(Param.PAGE_SIZE, "3")
      .setParam(Param.TEXT_QUERY, "file-name")
      .setParam(Param.ASCENDING, "true")
      .setParam(Param.SORT, "path"));

    assertThat(response.getComponentsCount()).isEqualTo(3);
    assertThat(response.getPaging().getTotal()).isEqualTo(9);
    assertThat(response.getComponentsList()).extracting("id").containsExactly("file-uuid-4", "file-uuid-5", "file-uuid-6");
  }

  @Test
  public void filter_descendants_by_qualifier() throws IOException {
    ComponentDto project = newProjectDto(db.organizations().insert(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    componentDb.insertComponent(newFileDto(project, 1));
    componentDb.insertComponent(newFileDto(project, 2));
    componentDb.insertComponent(newModuleDto("module-uuid-1", project));
    db.commit();
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "all")
      .setParam(PARAM_QUALIFIERS, Qualifiers.FILE)
      .setParam(PARAM_COMPONENT_ID, "project-uuid"));

    assertThat(response.getComponentsList()).extracting("id").containsExactly("file-uuid-1", "file-uuid-2");
  }

  @Test
  public void return_leaves() throws IOException {
    ComponentDto project = newProjectDto(db.getDefaultOrganization(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    ComponentDto module = newModuleDto("module-uuid-1", project);
    componentDb.insertComponent(module);
    componentDb.insertComponent(newFileDto(project, 1));
    componentDb.insertComponent(newFileDto(module, 2));
    ComponentDto directory = newDirectory(project, "directory-path-1");
    componentDb.insertComponent(directory);
    componentDb.insertComponent(newFileDto(module, directory, 3));
    db.commit();
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "leaves")
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .setParam(PARAM_QUALIFIERS, Qualifiers.FILE));

    assertThat(response.getComponentsCount()).isEqualTo(3);
    assertThat(response.getPaging().getTotal()).isEqualTo(3);
    assertThat(response.getComponentsList()).extracting("id").containsExactly("file-uuid-1", "file-uuid-2", "file-uuid-3");
  }

  @Test
  public void sort_descendants_by_qualifier() throws IOException {
    ComponentDto project = newProjectDto(db.organizations().insert(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    componentDb.insertComponent(newFileDto(project, 1));
    componentDb.insertComponent(newFileDto(project, 2));
    ComponentDto module = newModuleDto("module-uuid-1", project);
    componentDb.insertComponent(module);
    componentDb.insertComponent(newDirectory(project, "path/directory/", "directory-uuid-1"));
    db.commit();
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "all")
      .setParam(Param.SORT, "qualifier, name")
      .setParam(PARAM_COMPONENT_ID, "project-uuid"));

    assertThat(response.getComponentsList()).extracting("id").containsExactly("module-uuid-1", "path/directory/", "file-uuid-1", "file-uuid-2");
  }

  @Test
  public void return_children_of_a_view() {
    OrganizationDto organizationDto = db.organizations().insert();
    ComponentDto view = newView(organizationDto, "view-uuid");
    componentDb.insertViewAndSnapshot(view);
    ComponentDto project = newProjectDto(organizationDto, "project-uuid-1").setName("project-name").setKey("project-key-1");
    componentDb.insertProjectAndSnapshot(project);
    componentDb.insertComponent(newProjectCopy("project-uuid-1-copy", project, view));
    componentDb.insertComponent(newSubView(view, "sub-view-uuid", "sub-view-key").setName("sub-view-name"));
    db.commit();
    logInWithBrowsePermission(view);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_STRATEGY, "children")
      .setParam(PARAM_COMPONENT_ID, "view-uuid")
      .setParam(Param.TEXT_QUERY, "name"));

    assertThat(response.getComponentsList()).extracting("id").containsExactly("project-uuid-1-copy", "sub-view-uuid");
    assertThat(response.getComponentsList()).extracting("refId").containsExactly("project-uuid-1", "");
    assertThat(response.getComponentsList()).extracting("refKey").containsExactly("project-key-1", "");
  }

  @Test
  public void response_is_empty_on_provisioned_projects() {
    ComponentDto project = componentDb.insertComponent(newProjectDto(db.getDefaultOrganization(), "project-uuid"));
    logInWithBrowsePermission(project);

    TreeWsResponse response = call(ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid"));

    assertThat(response.getBaseComponent().getId()).isEqualTo("project-uuid");
    assertThat(response.getComponentsList()).isEmpty();
    assertThat(response.getPaging().getTotal()).isEqualTo(0);
    assertThat(response.getPaging().getPageSize()).isEqualTo(100);
    assertThat(response.getPaging().getPageIndex()).isEqualTo(1);
  }

  @Test
  public void return_developers() {
    ComponentDto project = newProjectDto(db.getDefaultOrganization(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    ComponentDto developer = newDeveloper(db.organizations().insert(), "developer-name");
    componentDb.insertDeveloperAndSnapshot(developer);
    componentDb.insertComponent(newDevProjectCopy("project-copy-uuid", project, developer));
    db.commit();
    logInWithBrowsePermission(developer);

    TreeWsResponse response = call(ws.newRequest().setParam(PARAM_COMPONENT_ID, developer.uuid()));

    assertThat(response.getBaseComponent().getId()).isEqualTo(developer.uuid());
    assertThat(response.getComponentsCount()).isEqualTo(1);
    assertThat(response.getComponents(0).getId()).isEqualTo("project-copy-uuid");
    assertThat(response.getComponents(0).getRefId()).isEqualTo("project-uuid");
  }

  @Test
  public void return_projects_composing_a_view() {
    ComponentDto project = newProjectDto(db.organizations().insert(), "project-uuid");
    componentDb.insertProjectAndSnapshot(project);
    ComponentDto view = newView(db.getDefaultOrganization(), "view-uuid");
    componentDb.insertViewAndSnapshot(view);
    componentDb.insertComponent(newProjectCopy("project-copy-uuid", project, view));
    logInWithBrowsePermission(view);

    TreeWsResponse response = call(ws.newRequest().setParam(PARAM_COMPONENT_ID, view.uuid()));

    assertThat(response.getBaseComponent().getId()).isEqualTo(view.uuid());
    assertThat(response.getComponentsCount()).isEqualTo(1);
    assertThat(response.getComponents(0).getId()).isEqualTo("project-copy-uuid");
    assertThat(response.getComponents(0).getRefId()).isEqualTo("project-uuid");
  }

  @Test
  public void fail_when_not_enough_privileges() {
    userSession.logIn()
      .addProjectUuidPermissions(UserRole.CODEVIEWER, "project-uuid");
    componentDb.insertComponent(newProjectDto(db.organizations().insert(), "project-uuid"));
    db.commit();

    expectedException.expect(ForbiddenException.class);

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .execute();
  }

  @Test
  public void fail_when_page_size_above_500() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The 'ps' parameter must be less than 500");
    componentDb.insertComponent(newProjectDto(db.getDefaultOrganization(), "project-uuid"));
    db.commit();

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .setParam(Param.PAGE_SIZE, "501")
      .execute();
  }

  @Test
  public void fail_when_search_query_has_less_than_3_characters() {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage("The 'q' parameter must have at least 3 characters");
    componentDb.insertComponent(newProjectDto(db.organizations().insert(), "project-uuid"));
    db.commit();

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .setParam(Param.TEXT_QUERY, "fi")
      .execute();
  }

  @Test
  public void fail_when_sort_is_unknown() {
    expectedException.expect(IllegalArgumentException.class);
    componentDb.insertComponent(newProjectDto(db.getDefaultOrganization(), "project-uuid"));
    db.commit();

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .setParam(Param.SORT, "unknown-sort")
      .execute();
  }

  @Test
  public void fail_when_strategy_is_unknown() {
    expectedException.expect(IllegalArgumentException.class);
    componentDb.insertComponent(newProjectDto(db.organizations().insert(), "project-uuid"));
    db.commit();

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .setParam(PARAM_STRATEGY, "unknown-strategy")
      .execute();
  }

  @Test
  public void fail_when_base_component_not_found() {
    expectedException.expect(NotFoundException.class);

    ws.newRequest()
      .setParam(PARAM_COMPONENT_ID, "project-uuid")
      .execute();
  }

  @Test
  public void fail_when_no_base_component_parameter() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Either 'componentId' or 'component' must be provided, not both");

    ws.newRequest().execute();
  }

  private TreeWsResponse call(TestRequest request) {
    try {
      return TreeWsResponse.parseFrom(request
        .setMediaType(MediaTypes.PROTOBUF)
        .execute().getInputStream());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static ComponentDto newFileDto(ComponentDto moduleOrProject, @Nullable ComponentDto directory, int i) {
    return ComponentTesting.newFileDto(moduleOrProject, directory, "file-uuid-" + i)
        .setName("file-name-" + i)
        .setKey("file-key-" + i)
        .setPath("file-path-" + i);
  }

  private static ComponentDto newFileDto(ComponentDto moduleOrProject, int i) {
    return newFileDto(moduleOrProject, null, i);
  }

  private ComponentDto initJsonExampleComponents() throws IOException {
    OrganizationDto organizationDto = db.organizations().insertForKey("my-org-1");
    ComponentDto project = newProjectDto(organizationDto, "MY_PROJECT_ID")
      .setKey("MY_PROJECT_KEY")
      .setName("Project Name");
    SnapshotDto projectSnapshot = componentDb.insertProjectAndSnapshot(project);
    Date now = new Date();
    JsonParser jsonParser = new JsonParser();
    JsonElement jsonTree = jsonParser.parse(IOUtils.toString(getClass().getResource("tree-example.json"), UTF_8));
    JsonArray components = jsonTree.getAsJsonObject().getAsJsonArray("components");
    for (JsonElement componentAsJsonElement : components) {
      JsonObject componentAsJsonObject = componentAsJsonElement.getAsJsonObject();
      String uuid = getJsonField(componentAsJsonObject, "id");
      componentDb.insertComponent(ComponentTesting.newChildComponent(uuid, project, project)
        .setKey(getJsonField(componentAsJsonObject, "key"))
        .setName(getJsonField(componentAsJsonObject, "name"))
        .setLanguage(getJsonField(componentAsJsonObject, "language"))
        .setPath(getJsonField(componentAsJsonObject, "path"))
        .setQualifier(getJsonField(componentAsJsonObject, "qualifier"))
        .setDescription(getJsonField(componentAsJsonObject, "description"))
        .setEnabled(true)
        .setCreatedAt(now));
    }
    db.commit();
    return project;
  }

  @CheckForNull
  private static String getJsonField(JsonObject jsonObject, String field) {
    JsonElement jsonElement = jsonObject.get(field);
    return jsonElement == null ? null : jsonElement.getAsString();
  }

  private void logInWithBrowsePermission(ComponentDto project) {
    userSession.logIn().addProjectUuidPermissions(UserRole.USER, project.uuid());
  }
}
