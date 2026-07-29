package com.mzinx.demo.web;

import org.bson.Document;
import org.bson.types.ObjectId;

/** Small helpers for JSON-friendly document responses. */
final class Documents {

    private Documents() {
    }

    /** Replaces an ObjectId {@code _id} with its hex string for clean JSON. */
    static void stringifyId(Document doc) {
        Object id = doc.get("_id");
        if (id instanceof ObjectId objectId)
            doc.put("_id", objectId.toHexString());
    }
}
