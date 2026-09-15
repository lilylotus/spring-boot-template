'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const PAGE_PATH = path.join(__dirname, 'echoIp.html');
const DEFAULT_PORT = 3000;

/**
 * 解析服务监听端口。
 *
 * @param {string | undefined} value 环境变量中的端口值
 * @returns {number} 合法端口
 */
function parsePort(value) {
    if (value === undefined || value === '') {
        return DEFAULT_PORT;
    }

    const port = Number(value);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`PORT 必须是 1 到 65535 之间的整数，当前值为: ${value}`);
    }
    return port;
}

/**
 * 将 Node.js 返回的 IPv4-映射 IPv6 地址转换为普通 IPv4 地址。
 *
 * @param {string | undefined} address 连接远端地址
 * @returns {string} 适合回显的地址
 */
function normalizeIp(address) {
    if (!address) {
        return '未知地址';
    }
    return address.startsWith('::ffff:') ? address.slice('::ffff:'.length) : address;
}

/**
 * 写入文本响应。
 *
 * @param {import('node:http').ServerResponse} response HTTP 响应
 * @param {number} statusCode 状态码
 * @param {string} contentType 媒体类型
 * @param {string} body 响应内容
 */
function send(response, statusCode, contentType, body) {
    response.writeHead(statusCode, {
        'Cache-Control': 'no-store',
        'Content-Type': contentType,
        'X-Content-Type-Options': 'nosniff'
    });
    response.end(body);
}

/**
 * 创建 IP 回显 HTTP 服务。
 *
 * @returns {import('node:http').Server} HTTP 服务
 */
function createServer() {
    return http.createServer((request, response) => {
        const requestUrl = new URL(request.url || '/', 'http://localhost');
        if (request.method !== 'GET') {
            send(response, 405, 'text/plain; charset=utf-8', '只支持 GET 请求');
            return;
        }

        if (requestUrl.pathname === '/api/ip') {
            const body = JSON.stringify({ ip: normalizeIp(request.socket.remoteAddress) });
            send(response, 200, 'application/json; charset=utf-8', body);
            return;
        }

        if (requestUrl.pathname === '/' || requestUrl.pathname === '/echoIp.html') {
            fs.readFile(PAGE_PATH, 'utf8', (error, page) => {
                if (error) {
                    console.error(`读取 echoIp.html 失败: ${error.message}`);
                    send(response, 500, 'text/plain; charset=utf-8', '页面暂时不可用');
                    return;
                }
                send(response, 200, 'text/html; charset=utf-8', page);
            });
            return;
        }

        send(response, 404, 'text/plain; charset=utf-8', '未找到请求的资源');
    });
}

if (require.main === module) {
    try {
        const port = parsePort(process.env.PORT);
        const server = createServer();
        server.on('error', error => {
            console.error(`IP 回显服务启动失败: ${error.message}`);
            process.exitCode = 1;
        });
        server.listen(port, '0.0.0.0', () => {
            console.log(`IP 回显服务已启动: http://127.0.0.1:${port}/echoIp.html`);
        });
    } catch (error) {
        console.error(`IP 回显服务启动失败: ${error.message}`);
        process.exitCode = 1;
    }
}

module.exports = { createServer, normalizeIp, parsePort };
