import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

// 编译真实 service/store，只有 HTTP 和 WebSocket 替换为可控测试端。
// 不连接设备，不发送真实寄存器配置，不需要额外安装测试框架。
const root = fileURLToPath(new URL("../", import.meta.url));
const bundle = await build({
    stdin: {
        contents: `export { jniBridgeService as service } from './src/service/jniBridgeService';
export { useJNIStore as store } from './src/store/jniStore';
export * from './src/utils/bridgeFeedback';`,
        resolveDir: root,
    },
    absWorkingDir: root,
    bundle: true,
    write: false,
    platform: "browser",
    format: "cjs",
    define: { "process.env.NODE_ENV": '"test"' },
    plugins: [{
        name: "mock-http",
        setup(builder) {
            builder.onResolve({ filter: /^\.\/jniService$/ }, () => ({ path: "api", namespace: "test" }));
            builder.onLoad({ filter: /.*/, namespace: "test" }, () => ({
                contents: "export const jniApi = globalThis.testApi;",
            }));
        },
    }],
});

function harness() {
    let socket;
    let sendCount = 0;
    const timers = new Set();
    const api = {
        sendFullConfig: async () => { sendCount++; },
        sendQueryStatus: async () => {},
        sendReset: async () => {},
    };
    class FakeSocket {
        static OPEN = 1;
        readyState = 1;
        constructor() {
            socket = this;
            queueMicrotask(() => this.onopen?.());
        }
        close() { this.readyState = 3; this.onclose?.(); }
    }
    const storage = { getItem: () => null, setItem() {}, removeItem() {} };
    const context = {
        module: { exports: {} }, console, Error, testApi: api, WebSocket: FakeSocket, localStorage: storage,
        window: {
            location: { protocol: "http:", host: "localhost" },
            setTimeout(fn, ms) { const id = setTimeout(fn, ms); timers.add(id); return id; },
            clearTimeout(id) { clearTimeout(id); timers.delete(id); },
        },
    };
    vm.runInNewContext(bundle.outputFiles[0].text, context);
    const { service, store } = context.module.exports;
    store.getState().actions.hydrateBridgeState({ connected: true, host: "test-board", controlPort: 5000, imagePort: 5001 });
    return {
        service, store, api, helpers: context.module.exports,
        get sendCount() { return sendCount; },
        emit(type, payload) { socket.onmessage({ data: JSON.stringify({ type, timestamp: "test-time", payload }) }); },
        close() { socket?.close(); for (const id of timers) clearTimeout(id); },
    };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));
const bytes = () => Array(512).fill(0);
const seedFeedback = (h) => {
    h.store.getState().actions.pushConfigAck({ id: "old", timestamp: "old", resultCode: 0, failedAddr: 0 });
    h.store.getState().actions.pushStatus({ id: "old", timestamp: "old", statusBits: 18, errorCode: 0, statusBinary: "10010" });
};

test("成功必须等 ACK=0；HTTP 返回不等于配置成功，旧状态被清除", async (t) => {
    const h = harness(); t.after(() => h.close()); seedFeedback(h);
    let settled = false;
    const result = h.service.sendFullConfigAndWait(bytes()).then((ack) => { settled = true; return ack; });
    await tick();
    assert.equal(settled, false);
    assert.equal(h.store.getState().latestConfigAck, null);
    assert.equal(h.store.getState().latestStatus, null);
    h.emit("config_ack", { resultCode: 0, failedAddr: 0 });
    assert.equal((await result).resultCode, 0);
    assert.equal(h.store.getState().latestStatus, null, "不能凭 ACK 伪造 STATUS");
    assert.doesNotMatch(h.helpers.describeConfigAck({ resultCode: 0, failedAddr: 0 }), /失败地址/);
});

for (const [code, reason] of [[1, /长度错误/], [2, /I\/O/], [3, /读回校验失败/], [4, /下位机忙/], [99, /未知/]]) {
    test(`配置应答 ${code} 应拒绝并保留真实应答`, async (t) => {
        const h = harness(); t.after(() => h.close());
        const rejected = assert.rejects(h.service.sendFullConfigAndWait(bytes()), reason);
        await tick(); h.emit("config_ack", { resultCode: code, failedAddr: 17 });
        await rejected;
        assert.equal(h.store.getState().latestConfigAck.resultCode, code);
        assert.match(h.store.getState().error, /D17（0x011）/);
    });
}

test("缺失、null 或字符串结果码均不能误判为成功；65535 不是有效地址", async (t) => {
    const h = harness(); t.after(() => h.close());
    for (const value of [undefined, null, "0", -1, 65536, 0.5]) {
        const rejected = assert.rejects(h.service.sendFullConfigAndWait(bytes()), /未知或无效/);
        await tick(); h.emit("config_ack", { resultCode: value, failedAddr: 65535 });
        await rejected;
        assert.match(h.store.getState().error, /未提供有效失败地址/);
        assert.doesNotMatch(h.store.getState().error, /D65535/);
    }
});

test("配置超时只报告结果未知，不保留旧成功", async (t) => {
    const h = harness(); t.after(() => h.close()); seedFeedback(h);
    await assert.rejects(h.service.sendFullConfigAndWait(bytes(), 15), /无法确认配置是否已应用/);
    assert.equal(h.store.getState().latestConfigAck, null);
});

test("HTTP 失败会结束等待，不产生未处理拒绝", async (t) => {
    const h = harness(); t.after(() => h.close());
    h.api.sendFullConfig = async () => { throw new Error("HTTP 配置发送失败"); };
    await assert.rejects(h.service.sendFullConfigAndWait(bytes()), /HTTP 配置发送失败/);
    h.api.sendQueryStatus = async () => { throw new Error("HTTP 查询失败"); };
    await assert.rejects(h.service.queryStatusAndWait(), /HTTP 查询失败/);
});

test("ACK 早于 HTTP 返回也能正确拒绝", async (t) => {
    const h = harness(); t.after(() => h.close());
    h.api.sendFullConfig = async () => {
        h.emit("config_ack", { resultCode: 3, failedAddr: 0 });
        await new Promise((resolve) => setTimeout(resolve, 15));
    };
    await assert.rejects(h.service.sendFullConfigAndWait(bytes()), /D0（0x000）/);
});

test("控制通道错误立即结束配置等待", async (t) => {
    const h = harness(); t.after(() => h.close());
    const rejected = assert.rejects(h.service.sendFullConfigAndWait(bytes()), /控制通道异常/);
    await tick(); h.emit("transport_error", { channel: "control", message: "TCP disconnected" });
    await rejected;
    assert.equal(h.store.getState().latestConfigAck, null);
});

test("WebSocket 断开、设备断开或换设备清除旧状态", async (t) => {
    const h = harness(); t.after(() => h.close());
    for (const invalidate of [
        () => h.store.getState().actions.setWebsocketConnected(false),
        () => h.store.getState().actions.hydrateBridgeState({ connected: false }),
        () => h.store.getState().actions.hydrateBridgeState({ connected: true, host: "other-board" }),
    ]) {
        seedFeedback(h); invalidate();
        assert.equal(h.store.getState().latestConfigAck, null);
        assert.equal(h.store.getState().latestStatus, null);
    }
});

test("复位仅表示命令发送，旧配置确认失效", async (t) => {
    const h = harness(); t.after(() => h.close()); seedFeedback(h);
    await h.service.sendReset();
    assert.equal(h.store.getState().latestConfigAck, null);
    assert.equal(h.store.getState().latestStatus, null);
});

test("状态应答保留真实数值，两套错误码不混用", async (t) => {
    const h = harness(); t.after(() => h.close());
    const result = h.service.queryStatusAndWait();
    await tick(); h.emit("status", { statusBits: 6, statusBinary: "00000110", errorCode: 2 });
    const status = await result;
    assert.equal(status.statusBits, 6);
    assert.match(h.helpers.describeStatusError(status.errorCode), /尚未成功应用配置/);
    assert.match(h.helpers.describeConfigAck({ resultCode: 2, failedAddr: 65535 }), /I\/O/);
    const invalid = h.service.queryStatusAndWait();
    await tick(); h.emit("status", {});
    assert.equal((await invalid).errorCode, -1);
});

test("非法字节阻止发送，不能静默截断", async (t) => {
    const h = harness(); t.after(() => h.close());
    for (const value of [-1, 256, 1.5, null]) {
        const data = bytes(); data[2] = value;
        await assert.rejects(h.service.sendFullConfigAndWait(data), /0～255/);
    }
    assert.equal(h.sendCount, 0);
});

test("重复配置请求不会覆盖正在等待的请求", async (t) => {
    const h = harness(); t.after(() => h.close());
    const first = h.service.sendFullConfigAndWait(bytes());
    await tick();
    await assert.rejects(h.service.sendFullConfigAndWait(bytes()), /已有配置命令/);
    assert.equal(h.sendCount, 1);
    h.emit("config_ack", { resultCode: 0, failedAddr: 0 });
    assert.equal((await first).resultCode, 0);
});

test("WebSocket 中断会拒绝正在等待的配置", async (t) => {
    const h = harness(); t.after(() => h.close());
    const rejected = assert.rejects(h.service.sendFullConfigAndWait(bytes()), /配置应答通道已关闭/);
    await tick(); h.close();
    await rejected;
});
