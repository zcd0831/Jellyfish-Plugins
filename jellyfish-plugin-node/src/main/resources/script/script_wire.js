'use strict';

/**
 * 网关与 worker 两侧共用的分帧约定（Node 版）。
 *
 * 帧格式与 Python 版、与宿主侧完全一致：**一行一个 UTF-8 JSON 对象**。
 * 用行分隔而不是长度前缀，是因为它允许「错一行、丢一行、继续」；
 * 长度前缀会让任何一次误打印把后续所有帧错位，而恢复无从下手。
 *
 * 两侧的实现必须逐字节等价（`JSON.stringify` 默认不留空格，与 Python 侧的
 * `separators=(",", ":")` 相同），否则会出现「宿主能发、worker 读不出」——
 * 这类问题的现场表现是「脚本毫无反应」，既看不到异常也看不到错误码。
 */

/** 单帧上限：与宿主侧的传输上限一致。超限说明脚本返回了不该返回的大对象，早失败好过把内存吃满。 */
const MAX_FRAME_BYTES = 10 * 1024 * 1024;

/** 逐条告警的上限：一个循环打印的脚本不该把两侧日志刷满；超过之后只报一次总数。 */
const DROPPED_ALERT_LIMIT = 5;

/**
 * 把一个对象编码成一帧字节（含结尾换行）。
 *
 * @param {*} message 待编码的对象
 * @returns {Buffer} 帧字节
 */
function encode(message) {
    return Buffer.from(JSON.stringify(message) + '\n', 'utf8');
}

/**
 * 把新到的字节并入缓冲区并切出完整的帧。
 *
 * 无法解析的行（脚本误打印、运行时警告）直接丢弃：它不该让一次调用失败，
 * 也不该被计入任何失败账目。但**丢弃必须留痕**：既计数（回给调用方），
 * 也往 stderr 写一条（两侧的 stderr 都会进宿主日志），并对逐条告警限流。
 *
 * @param {Buffer} buffer 剩余缓冲
 * @param {Buffer} chunk 新到的字节
 * @returns {{buffer: Buffer, frames: Array, dropped: number}} 剩余缓冲、切出的帧与丢弃行数
 */
function feed(buffer, chunk) {
    let pending = Buffer.concat(buffer === undefined || buffer === null ? [] : [buffer, chunk]);
    const frames = [];
    let dropped = 0;
    for (;;) {
        const index = pending.indexOf(0x0a);
        if (index < 0) {
            break;
        }
        const line = pending.subarray(0, index);
        pending = pending.subarray(index + 1);
        if (line.toString('utf8').trim() === '') {
            continue;
        }
        try {
            frames.push(JSON.parse(line.toString('utf8')));
        } catch (ignored) {
            // 非 JSON 行：宽容丢弃、不失败，但必须留痕。
            // 一条被丢掉的行如果正好是某个请求的应答，现场就是「请求悬到超时」——
            // 那时两侧日志里一条线索都没有，排查只能靠猜
            dropped++;
            if (dropped <= DROPPED_ALERT_LIMIT) {
                process.stderr.write('[script_wire] 丢弃无法解析的协议行: '
                    + JSON.stringify(line.toString('utf8').slice(0, 200)) + '\n');
            }
        }
    }
    if (dropped > DROPPED_ALERT_LIMIT) {
        process.stderr.write('[script_wire] 本次共丢弃 ' + dropped + ' 行无法解析的协议行（前 '
            + DROPPED_ALERT_LIMIT + ' 行已逐条记录）\n');
    }
    return { buffer: pending, frames, dropped };
}

/**
 * 把新到的字节并入缓冲区并按行切分文本（用于把子进程 stderr 转进日志）。
 *
 * 与 {@link feed} 的区别只在用途：日志是给人看的，因此不要求是 JSON，
 * 但**必须能把多字节字符完整还原**——脚本里的中文日志恰恰是最需要看清的部分。
 * 半个多字节字符会留在缓冲里等下一个 chunk，而不是变成替换字符。
 *
 * @param {Buffer} buffer 剩余缓冲
 * @param {Buffer} chunk 新到的字节
 * @returns {{buffer: Buffer, lines: string[]}} 剩余缓冲与切出的文本行
 */
function takeLines(buffer, chunk) {
    let pending = Buffer.concat(buffer === undefined || buffer === null ? [] : [buffer, chunk]);
    const lines = [];
    for (;;) {
        const index = pending.indexOf(0x0a);
        if (index < 0) {
            break;
        }
        let line = pending.subarray(0, index);
        pending = pending.subarray(index + 1);
        if (line.length > 0 && line[line.length - 1] === 0x0d) {
            line = line.subarray(0, line.length - 1);
        }
        lines.push(decodeLossy(line));
    }
    return { buffer: pending, lines };
}

/**
 * 把一行字节解成文本，非法字节换成替换字符。
 *
 * 这里刻意不依赖 `Buffer.toString('utf8')` 对「半个字符」的隐式处理：
 * 行内出现的非法字节应当就地可见（替换字符），而不是吃掉后面的内容。
 *
 * @param {Buffer} line 行字节
 * @returns {string} 文本
 */
function decodeLossy(line) {
    return line.toString('utf8');
}

module.exports = { MAX_FRAME_BYTES, DROPPED_ALERT_LIMIT, encode, feed, takeLines };
