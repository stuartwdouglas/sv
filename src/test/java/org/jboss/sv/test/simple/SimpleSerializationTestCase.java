package org.jboss.sv.test.simple;

import org.jboss.sv.SerializedView;
import org.junit.Assert;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

/**
 * @author Stuart Douglas
 */
public class SimpleSerializationTestCase {

    private static final SerializedView<Client> SIMPLE_CLIENT = SerializedView.builder(Client.class).build();

    private static final SerializedView<Client> CLIENT_WITH_DELIVERY_ADDRESS = SerializedView.builder(Client.class)
            .include("deliveryAddress", SerializedView.builder(Address.class))
            .build();

    private static final SerializedView<Client> CLIENT_JUST_NAME = SerializedView.builder(Client.class)
            .defaultExclude()
            .include("name")
            .build();

    private static final SerializedView<Client> CLIENT_WITH_ORDERS = SerializedView.builder(Client.class)
            .include("orders", SerializedView.builder(Order.class))
            .build();
    @Test
    public void testSerialization() {
        Client c = createClient();
        String json = SIMPLE_CLIENT.serialize(c);
        JsonReader parser = Json.createReader(new ByteArrayInputStream(json.getBytes(Charset.forName("UTF-8"))));
        JsonObject obj = parser.readObject();
        Assert.assertEquals(2, obj.keySet().size());
        Assert.assertEquals(1, obj.getInt("id"));
        Assert.assertEquals("Client 1", obj.getString("name"));

        Client des = SIMPLE_CLIENT.deserialize(json);
        Assert.assertEquals("Client 1", des.getName());
        Assert.assertEquals(1, des.getId());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertNull(des.getDeliveryAddress());
        Assert.assertEquals(0, des.getOrders().size());


        c = createClient();
        json = CLIENT_WITH_DELIVERY_ADDRESS.serialize(c);
        parser = Json.createReader(new ByteArrayInputStream(json.getBytes(Charset.forName("UTF-8"))));
        obj = parser.readObject();
        Assert.assertEquals(3, obj.keySet().size());
        Assert.assertEquals(1, obj.getInt("id"));
        Assert.assertEquals("Client 1", obj.getString("name"));
        obj = obj.getJsonObject("deliveryAddress");
        Assert.assertEquals(4, obj.keySet().size());
        Assert.assertEquals("Wollongong", obj.getString("city"));

        des = CLIENT_WITH_DELIVERY_ADDRESS.deserialize(json);
        Assert.assertEquals("Client 1", des.getName());
        Assert.assertEquals(1, des.getId());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertEquals(0, des.getOrders().size());
        Assert.assertEquals("Wollongong", des.getDeliveryAddress().getCity());

        c = createClient();
        json = CLIENT_JUST_NAME.serialize(c);
        parser = Json.createReader(new ByteArrayInputStream(json.getBytes(Charset.forName("UTF-8"))));
        obj = parser.readObject();
        Assert.assertEquals(1, obj.keySet().size());
        Assert.assertEquals("Client 1", obj.getString("name"));

        des = CLIENT_JUST_NAME.deserialize(json);
        Assert.assertEquals("Client 1", des.getName());
        Assert.assertEquals(0, des.getId());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertEquals(0, des.getOrders().size());

        c = createClient();
        json = CLIENT_WITH_ORDERS.serialize(c);
        parser = Json.createReader(new ByteArrayInputStream(json.getBytes(Charset.forName("UTF-8"))));
        obj = parser.readObject();
        Assert.assertEquals(3, obj.keySet().size());
        Assert.assertEquals("Client 1", obj.getString("name"));
        JsonArray array = obj.getJsonArray("orders");
        Assert.assertEquals(2, array.size());
        obj = (JsonObject) array.get(0);
        Assert.assertEquals("A Chair", obj.getString("name"));

        des = CLIENT_WITH_ORDERS.deserialize(json);
        Assert.assertEquals("Client 1", des.getName());
        Assert.assertEquals(1, des.getId());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertNull(des.getBillingAddress());
        Assert.assertEquals(2, des.getOrders().get(1).getId());
        Assert.assertEquals("A Desk", des.getOrders().get(1).getName());
    }

    private Client createClient() {
        Client c = new Client();
        c.setId(1);
        c.setName("Client 1");
        Address billingAddress = new Address();
        billingAddress.setCity("Orange");
        billingAddress.setState("NSW");
        billingAddress.setFirst("1 Foo Place");
        billingAddress.setPostcode(2800);
        c.setBillingAddress(billingAddress);

        Address deliveryAddress = new Address();
        deliveryAddress.setCity("Wollongong");
        deliveryAddress.setState("NSW");
        deliveryAddress.setFirst("123 Fake Street");
        deliveryAddress.setPostcode(2500);
        c.setDeliveryAddress(deliveryAddress);

        c.getOrders().add(new Order(1, "A Chair"));
        c.getOrders().add(new Order(2, "A Desk"));
        return c;
    }
}
