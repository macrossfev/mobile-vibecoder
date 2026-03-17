#!/usr/bin/env python3
"""
语音代理层 - Mobile VibeCoder服务端

功能：
1. 拦截tmux会话输出
2. 检测Claude工作状态
3. 提炼适合语音播报的内容
4. 通过HTTP API提供服务

使用方法：
    python voice_proxy.py --session 101 --port 8765
"""

import argparse
import subprocess
import time
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import yaml
from typing import Optional
from dataclasses import dataclass, asdict

from state_detector import StateDetector, ClaudeState
from content_summarizer import ContentSummarizer

@dataclass
class VoiceProxyState:
    session: str
    current_output: str
    state: str
    speech_content: str
    last_update: float

class VoiceProxy:
    """语音代理主类"""

    def __init__(self, session: str, config_path: str = None):
        self.session = session
        self.config = self._load_config(config_path) if config_path else {}
        self.state_detector = StateDetector(config_path)
        self.summarizer = ContentSummarizer(
            max_length=self.config.get('content_filter', {}).get('max_length', 5000)
        )
        self.state = VoiceProxyState(
            session=session,
            current_output="",
            state="idle",
            speech_content="",
            last_update=time.time()
        )
        self._running = False
        self._thread: Optional[threading.Thread] = None

    def _load_config(self, path: str) -> dict:
        """加载配置文件"""
        try:
            with open(path, 'r') as f:
                return yaml.safe_load(f)
        except Exception as e:
            print(f"[VoiceProxy] 配置加载失败: {e}")
            return {}

    def capture_output(self) -> str:
        """捕获tmux会话输出"""
        try:
            result = subprocess.run(
                ['tmux', 'capture-pane', '-t', self.session, '-p'],
                capture_output=True,
                text=True,
                timeout=5
            )
            return result.stdout
        except subprocess.TimeoutExpired:
            print(f"[VoiceProxy] 捕获超时: {self.session}")
            return self.state.current_output
        except Exception as e:
            print(f"[VoiceProxy] 捕获失败: {e}")
            return ""

    def poll_loop(self):
        """轮询主循环"""
        poll_interval = self.config.get('tmux', {}).get('poll_interval_ms', 500) / 1000

        while self._running:
            try:
                # 捕获输出
                output = self.capture_output()
                self.state.current_output = output
                self.state.last_update = time.time()

                # 检测状态
                state_result = self.state_detector.detect(output)
                self.state.state = state_result.state.value

                # 如果状态为完成，提炼语音内容
                if state_result.state == ClaudeState.COMPLETED:
                    self.state.speech_content = self.summarizer.extract_summary(output)

                time.sleep(poll_interval)

            except Exception as e:
                print(f"[VoiceProxy] 轮询错误: {e}")
                time.sleep(1)

    def start(self):
        """启动代理"""
        self._running = True
        self._thread = threading.Thread(target=self.poll_loop, daemon=True)
        self._thread.start()
        print(f"[VoiceProxy] 已启动，监听会话: {self.session}")

    def stop(self):
        """停止代理"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=2)
        print("[VoiceProxy] 已停止")

    def get_status(self) -> dict:
        """获取当前状态"""
        return asdict(self.state)


class VoiceProxyHandler(BaseHTTPRequestHandler):
    """HTTP请求处理器"""

    proxy: VoiceProxy = None

    def log_message(self, format, *args):
        """禁用默认日志"""
        pass

    def do_GET(self):
        """处理GET请求"""
        if self.path == '/status':
            self._send_json(self.proxy.get_status())
        elif self.path == '/speech':
            self._send_json({
                'content': self.proxy.state.speech_content,
                'state': self.proxy.state.state
            })
        elif self.path == '/output':
            self._send_json({
                'output': self.proxy.state.current_output[-5000:]  # 最近5000字符
            })
        else:
            self.send_error(404, 'Not Found')

    def do_POST(self):
        """处理POST请求"""
        if self.path == '/refresh':
            self.proxy.state.speech_content = ""
            self._send_json({'status': 'ok'})
        else:
            self.send_error(404, 'Not Found')

    def _send_json(self, data: dict):
        """发送JSON响应"""
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description='Voice Proxy for Mobile VibeCoder')
    parser.add_argument('--session', required=True, help='tmux会话名')
    parser.add_argument('--port', type=int, default=8765, help='HTTP服务端口')
    parser.add_argument('--config', default='config.yaml', help='配置文件路径')
    args = parser.parse_args()

    # 创建代理
    proxy = VoiceProxy(args.session, args.config)
    VoiceProxyHandler.proxy = proxy

    # 启动轮询
    proxy.start()

    # 启动HTTP服务
    server = HTTPServer(('0.0.0.0', args.port), VoiceProxyHandler)
    print(f"[VoiceProxy] HTTP服务已启动: http://0.0.0.0:{args.port}")
    print(f"[VoiceProxy] API端点:")
    print(f"  GET /status  - 获取完整状态")
    print(f"  GET /speech  - 获取语音播报内容")
    print(f"  GET /output  - 获取原始输出")
    print(f"  POST /refresh - 清除语音内容")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[VoiceProxy] 正在停止...")
        proxy.stop()
        server.shutdown()


if __name__ == '__main__':
    main()