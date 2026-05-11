package com.mycompany.p2pchat.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mycompany.p2pchat.model.Message;

public class JsonUtil {

    private static final Gson GSON = new GsonBuilder().create();

    public static String toJson(Message message) {
        return GSON.toJson(message);
    }

    public static Message fromJson(String json) {
        return GSON.fromJson(json, Message.class);
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }
}
