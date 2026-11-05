package com.zhishiku.notifier;

import java.net.URI;

/** 实现必须同步等到尝试退出；不能在返回或抛出后继续发送。 */
interface Transport {
    Response exchange(URI uri, byte[] body, Deadline deadline) throws Exception;

    record Response(int status, byte[] body) {
        @Override
        public String toString() {
            return "Response[redacted]";
        }
    }
}
