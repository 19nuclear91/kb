package com.zhishiku.kb.worker.storage;

import java.io.InputStream;

public interface ObjectStorage {
    InputStream get(String objectKey);
}
