/*
 * Copyright 2013 The Apache Software Foundation.
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
 */
package org.apache.olingo.odata2.core.annotation.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.olingo.odata2.api.annotation.edm.EdmKey;
import org.apache.olingo.odata2.api.annotation.edm.EdmProperty;
import org.apache.olingo.odata2.api.data.DataSource;
import org.apache.olingo.odata2.api.edm.EdmEntitySet;
import org.apache.olingo.odata2.api.edm.EdmEntityType;
import org.apache.olingo.odata2.api.edm.FullQualifiedName;
import org.apache.olingo.odata2.api.edm.provider.EntitySet;
import org.apache.olingo.odata2.api.exception.ODataException;
import org.apache.olingo.odata2.core.annotation.edm.AnnotationEdmProvider;
import org.apache.olingo.odata2.core.annotation.model.Building;
import org.apache.olingo.odata2.core.annotation.model.ModelSharedConstants;
import org.apache.olingo.odata2.core.annotation.model.Photo;
import org.apache.olingo.odata2.core.annotation.model.Room;
import org.apache.olingo.odata2.core.annotation.util.AnnotationHelper;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;

/**
 *
 */
public class AnnotationsInMemoryDsTest {

  private final AnnotationInMemoryDs datasource;
  private final AnnotationEdmProvider edmProvider;
  private static final String DEFAULT_CONTAINER = ModelSharedConstants.CONTAINER_1;

  public AnnotationsInMemoryDsTest() throws ODataException {
    datasource = new AnnotationInMemoryDs(Building.class.getPackage().getName(), false);
    edmProvider = new AnnotationEdmProvider(Building.class.getPackage().getName());
  }

  @Test
  @Ignore
  public void multiThreadedSyncOnBuildingsTest() throws Exception {
    final EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Buildings");
    CountDownLatch latch;

    List<Thread> threads = new ArrayList<Thread>();
    int max = 500;

    latch = new CountDownLatch(max);
    for (int i = 0; i < max; i++) {
      threads.add(createBuildingThread(latch, datasource, edmEntitySet, String.valueOf("10")));
    }

    for (Thread thread : threads) {
      thread.start();
    }

    latch.await(60, TimeUnit.SECONDS);

    DataStore<Building> ds = datasource.getDataStore(Building.class);
    Collection<Building> buildings = ds.read();
    Assert.assertEquals(max, buildings.size());
  }

  @org.apache.olingo.odata2.api.annotation.edm.EdmEntitySet
  @org.apache.olingo.odata2.api.annotation.edm.EdmEntityType
  private static class SimpleEntity {
    @EdmKey
    @EdmProperty
    public Integer id;
    @EdmProperty
    public String name;
  }

  @Test
  @Ignore
  public void multiThreadedSyncCreateReadTest() throws Exception {
    Collection<Class<?>> ac = new ArrayList<Class<?>>();
    ac.add(SimpleEntity.class);
    final AnnotationInMemoryDs localDs = new AnnotationInMemoryDs(SimpleEntity.class.getPackage().getName(), true);
    final AnnotationEdmProvider localProvider = new AnnotationEdmProvider(ac);
    final EdmEntitySet edmEntitySet = createMockedEdmEntitySet(localProvider, "SimpleEntitySet");
    final CountDownLatch latch;

    List<Thread> threads = new ArrayList<Thread>();
    int max = 500;
    latch = new CountDownLatch(max);
    for (int i = 0; i < max; i++) {
      Runnable run = new Runnable() {
        @Override
        public void run() {
          SimpleEntity se = new SimpleEntity();
          se.id = Integer.valueOf(String.valueOf(System.currentTimeMillis()).substring(8));
          se.name = "Name: " + System.currentTimeMillis();
          try {
            localDs.createData(edmEntitySet, se);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }finally{
            latch.countDown();
          }
        }
      };

      threads.add(new Thread(run));
    }

    for (Thread thread : threads) {
      thread.start();
    }

    latch.await(60, TimeUnit.SECONDS);

    DataStore<SimpleEntity> ds = localDs.getDataStore(SimpleEntity.class);
    Collection<SimpleEntity> buildings = ds.read();
    Assert.assertEquals(max, buildings.size());
  }

  private Thread createBuildingThread(final CountDownLatch latch, final DataSource datasource,
      final EdmEntitySet edmEntitySet, final String id) {
    Runnable run = new Runnable() {
      @Override
      public void run() {
        Building building = new Building();
        building.setName("Common Building - " + System.currentTimeMillis());
        building.setId(id);
        try {
          datasource.createData(edmEntitySet, building);
        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        } finally {
          latch.countDown();
        }
      }
    };

    return new Thread(run);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void readRelatedEntities() throws Exception {
    EdmEntitySet buildingsEntitySet = createMockedEdmEntitySet("Buildings");
    EdmEntitySet roomsEntitySet = createMockedEdmEntitySet("Rooms");

    Building building = new Building();
    building.setName("Common Building");

    final int roomsCount = 10;
    List<Room> rooms = new ArrayList<Room>();
    for (int i = 0; i < roomsCount; i++) {
      Room room = new Room(i, "Room " + i);
      room.setBuilding(building);
      datasource.createData(roomsEntitySet, room);
      rooms.add(room);
    }

    building.getRooms().addAll(rooms);
    datasource.createData(buildingsEntitySet, building);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Id", "1");

    Building read = (Building) datasource.readData(buildingsEntitySet, keys);
    Assert.assertEquals("Common Building", read.getName());
    Assert.assertEquals("1", read.getId());

    // execute
    Object relatedData = datasource.readRelatedData(
        buildingsEntitySet, building, roomsEntitySet, Collections.EMPTY_MAP);

    // validate
    Assert.assertTrue("Result is no collection.", relatedData instanceof Collection);
    Collection<Room> relatedRooms = (Collection<Room>) relatedData;
    Assert.assertEquals(roomsCount, relatedRooms.size());
    for (Room room : relatedRooms) {
      Assert.assertNotNull(room.getId());
      Assert.assertTrue(room.getName().matches("Room \\d*"));
      Assert.assertEquals("Common Building", room.getBuilding().getName());
    }
  }

  @Test
  public void readRelatedTargetEntity() throws Exception {
    EdmEntitySet buildingsEntitySet = createMockedEdmEntitySet("Buildings");
    EdmEntitySet roomsEntitySet = createMockedEdmEntitySet("Rooms");

    Building building = new Building();
    building.setName("Common Building");

    final int roomsCount = 10;
    List<Room> rooms = new ArrayList<Room>();
    for (int i = 0; i < roomsCount; i++) {
      Room room = new Room(i, "Room " + i);
      room.setBuilding(building);
      datasource.createData(roomsEntitySet, room);
      rooms.add(room);
    }

    building.getRooms().addAll(rooms);
    datasource.createData(buildingsEntitySet, building);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Id", "1");

    Building read = (Building) datasource.readData(buildingsEntitySet, keys);
    Assert.assertEquals("Common Building", read.getName());
    Assert.assertEquals("1", read.getId());

    // execute
    Map<String, Object> targetKeys = new HashMap<String, Object>();
    targetKeys.put("Id", 3);
    Object relatedData = datasource.readRelatedData(
        buildingsEntitySet, building, roomsEntitySet, targetKeys);

    // validate
    Assert.assertTrue("Result is no Room.", relatedData instanceof Room);
    Room relatedRoom = (Room) relatedData;
    Assert.assertEquals("3", relatedRoom.getId());
    Assert.assertEquals("Room 3", relatedRoom.getName());
    Assert.assertEquals("Common Building", relatedRoom.getBuilding().getName());
  }

  @Test
  public void createSimpleEntity() throws Exception {
    EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Buildings");

    Building building = new Building();
    building.setName("Common Building");
    datasource.createData(edmEntitySet, building);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Id", "1");

    Building read = (Building) datasource.readData(edmEntitySet, keys);
    Assert.assertEquals("Common Building", read.getName());
    Assert.assertEquals("1", read.getId());
  }

  @Test
  public void createSimpleEntityWithOwnKey() throws Exception {
    EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Buildings");

    Building building = new Building();
    building.setName("Common Building");
    AnnotationHelper ah = new AnnotationHelper();
    ah.setValueForProperty(building, "Id", "42");
    datasource.createData(edmEntitySet, building);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Id", "42");

    Building read = (Building) datasource.readData(edmEntitySet, keys);
    Assert.assertEquals("Common Building", read.getName());
    Assert.assertEquals("42", read.getId());
  }

  @Test
  public void createSimpleEntityWithDuplicateKey() throws Exception {
    EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Buildings");
    AnnotationHelper ah = new AnnotationHelper();

    Building building = new Building();
    building.setName("Common Building");
    ah.setValueForProperty(building, "Id", "42");
    datasource.createData(edmEntitySet, building);
    //
    Building buildingDuplicate = new Building();
    buildingDuplicate.setName("Duplicate Building");
    ah.setValueForProperty(buildingDuplicate, "Id", "42");
    datasource.createData(edmEntitySet, buildingDuplicate);

    Map<String, Object> keys42 = new HashMap<String, Object>();
    keys42.put("Id", "42");
    Building read42 = (Building) datasource.readData(edmEntitySet, keys42);
    Assert.assertEquals("Common Building", read42.getName());
    Assert.assertEquals("42", read42.getId());

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Id", "1");
    Building read = (Building) datasource.readData(edmEntitySet, keys);
    Assert.assertEquals("Duplicate Building", read.getName());
    Assert.assertEquals("1", read.getId());
  }

  @Test
  public void createEntityTwoKeys() throws Exception {
    EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Photos");

    Photo photo = new Photo();
    photo.setName("BigPicture");
    photo.setType("PNG");
    photo.setImageUri("https://localhost/image.png");
    photo.setImageType("image/png");
    datasource.createData(edmEntitySet, photo);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("ImageFormat", "PNG");
    keys.put("Name", "BigPicture");

    Photo read = (Photo) datasource.readData(edmEntitySet, keys);
    Assert.assertEquals("BigPicture", read.getName());
    Assert.assertEquals("PNG", read.getType());
    Assert.assertEquals("image/png", read.getImageType());
    Assert.assertEquals("https://localhost/image.png", read.getImageUri());
  }

  @Test
  public void createAndUpdateEntityTwoKeys() throws Exception {
    EdmEntitySet edmEntitySet = createMockedEdmEntitySet("Photos");

    Photo photo = new Photo();
    final String nameKeyValue = "BigPicture";
    final String typeKeyValue = "PNG";
    photo.setName(nameKeyValue);
    photo.setType(typeKeyValue);
    photo.setImageUri("https://localhost/image.png");
    photo.setImageType("image/png");
    datasource.createData(edmEntitySet, photo);

    Map<String, Object> keys = new HashMap<String, Object>();
    keys.put("Name", "BigPicture");
    keys.put("ImageFormat", "PNG");

    Photo read = (Photo) datasource.readData(edmEntitySet, keys);
    Assert.assertEquals("BigPicture", read.getName());
    Assert.assertEquals("PNG", read.getType());
    Assert.assertEquals("image/png", read.getImageType());
    Assert.assertEquals("https://localhost/image.png", read.getImageUri());

    // update
    Photo updatedPhoto = new Photo();
    updatedPhoto.setName(nameKeyValue);
    updatedPhoto.setType(typeKeyValue);
    updatedPhoto.setImageUri("https://localhost/image.jpg");
    updatedPhoto.setImageType("image/jpg");
    datasource.updateData(edmEntitySet, updatedPhoto);

    Map<String, Object> updatedKeys = new HashMap<String, Object>();
    updatedKeys.put("Name", nameKeyValue);
    updatedKeys.put("ImageFormat", typeKeyValue);

    Photo readUpdated = (Photo) datasource.readData(edmEntitySet, updatedKeys);
    Assert.assertEquals("BigPicture", readUpdated.getName());
    Assert.assertEquals("PNG", readUpdated.getType());
    Assert.assertEquals("image/jpg", readUpdated.getImageType());
    Assert.assertEquals("https://localhost/image.jpg", readUpdated.getImageUri());
  }

  private EdmEntitySet createMockedEdmEntitySet(final String entitySetName) throws ODataException {
    return createMockedEdmEntitySet(edmProvider, entitySetName);
  }

  private EdmEntitySet createMockedEdmEntitySet(AnnotationEdmProvider edmProvider, final String entitySetName)
      throws ODataException {
    EntitySet entitySet = edmProvider.getEntitySet(DEFAULT_CONTAINER, entitySetName);
    FullQualifiedName entityType = entitySet.getEntityType();

    EdmEntitySet edmEntitySet = Mockito.mock(EdmEntitySet.class);
    Mockito.when(edmEntitySet.getName()).thenReturn(entitySetName);
    EdmEntityType edmEntityType = Mockito.mock(EdmEntityType.class);
    Mockito.when(edmEntitySet.getEntityType()).thenReturn(edmEntityType);
    Mockito.when(edmEntityType.getName()).thenReturn(entityType.getName());

    return edmEntitySet;
  }
}
