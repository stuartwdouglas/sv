package org.jboss.sv.test.simple;

import javax.json.JsonObject;

/**
 * @author Stuart Douglas
 */
public class Order {

    private final int id;
    private final String name;

    public Order(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public static Order deserialize(JsonObject obj) {
        return new Order(obj.getInt("id"), obj.getString("name"));
    }
}
