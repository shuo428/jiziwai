import type { ConfigAckRecord } from "../types/jni";

// control_3v 的 CONFIG_ACK 结果码与 STATUS 错误码是两套枚举，不能共用映射。
const CONFIG_RESULTS: Record<number, string> = {
    0: "下位机确认配置应用成功",
    1: "配置长度错误，下位机要求 512 字节",
    2: "配置 I/O 或初始化失败，请检查板端日志",
    3: "寄存器读回校验失败，写入值与读回值不一致",
    4: "下位机忙，本次配置未应用",
};

const STATUS_ERRORS: Record<number, string> = {
    0: "未报告错误",
    1: "通信协议错误",
    2: "当前连接尚未成功应用配置，请先下发配置",
    3: "下位机忙",
    4: "配置 I/O 或初始化失败",
    5: "寄存器读回校验失败",
    6: "采集启动失败",
    7: "DMA 接收失败",
    8: "DMA 图像长度与约定不一致",
    9: "图像 TCP 发送失败",
    10: "复位失败",
};

/** 不把 null、空字符串或缺失字段转换成 0，以免损坏的应答被误报为成功。 */
export const readWireUint16 = (value: unknown): number =>
    typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= 0xffff
        ? value
        : -1;

export const describeConfigAck = (ack: Pick<ConfigAckRecord, "resultCode" | "failedAddr">): string => {
    const reason = CONFIG_RESULTS[ack.resultCode] ?? "未知或无效的配置应答，请核对下位机协议";
    const result = `${reason}（resultCode=${ack.resultCode}）`;
    // 成功应答中的 failedAddr=0 是占位值，不表示 D0 失败。
    if (ack.resultCode === 0) return result;
    const validAddress = Number.isInteger(ack.failedAddr) && ack.failedAddr >= 0 && ack.failedAddr < 512;
    const address = validAddress
        ? `失败地址：D${ack.failedAddr}（0x${ack.failedAddr.toString(16).toUpperCase().padStart(3, "0")}）`
        : `下位机未提供有效失败地址（failedAddr=${ack.failedAddr}）`;
    return `${result}；${address}`;
};

export const describeStatusError = (code: number): string =>
    `${STATUS_ERRORS[code] ?? "未知或无效的设备错误码"}（errorCode=${code}）`;

export const DEVICE_STATUS_FLAGS = [
    { mask: 1 << 0, label: "BUSY：忙" },
    { mask: 1 << 1, label: "READY：空闲" },
    { mask: 1 << 2, label: "ERROR_FLAG：错误标志" },
    { mask: 1 << 3, label: "IMAGE_READY：图像就绪" },
    { mask: 1 << 4, label: "CONFIG_APPLIED：配置已应用" },
];
