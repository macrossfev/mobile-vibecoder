#!/usr/bin/env python3
"""
状态检测器 - 检测Claude工作状态

状态类型：
- WORKING: Claude正在处理（思考、读取、执行工具）
- COMPLETED: 任务完成，等待新输入
- IDLE: 空闲状态

检测方法：
1. 进度动画检测（⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏）
2. 关键词检测（Thinking, Reading, Analyzing）
3. 输出稳定性检测（连续N次相同输出）
"""

import re
from enum import Enum
from typing import Optional
from dataclasses import dataclass
import yaml

class ClaudeState(Enum):
    WORKING = "working"
    COMPLETED = "completed"
    IDLE = "idle"

@dataclass
class StateResult:
    state: ClaudeState
    confidence: float  # 0.0 - 1.0
    reason: str

class StateDetector:
    """Claude工作状态检测器"""

    # 工作状态指示器
    WORKING_PATTERNS = [
        r'Thinking\.?\.?',
        r'Reading\.?\.?',
        r'Analyzing\.?\.?',
        r'Processing',
        r'Executing',
        r'[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]',  # 进度动画
        r'^\s*\.{3,}\s*$',  # 省略号
    ]

    # 完成状态指示器
    COMPLETION_PATTERNS = [
        r'任务.*完成',
        r'验收',
        r'报告.*完成',
        r'请.*确认',
        r'等待.*输入',
        r'---\s*$',  # 分隔线后
    ]

    def __init__(self, config_path: str = None):
        self.working_patterns = [re.compile(p, re.IGNORECASE) for p in self.WORKING_PATTERNS]
        self.completion_patterns = [re.compile(p, re.IGNORECASE) for p in self.COMPLETION_PATTERNS]
        self.last_output = ""
        self.stable_count = 0

        if config_path:
            self._load_config(config_path)

    def _load_config(self, path: str):
        """加载配置文件"""
        try:
            with open(path, 'r') as f:
                config = yaml.safe_load(f)
                if 'state_detection' in config:
                    sd = config['state_detection']
                    if 'working_indicators' in sd:
                        self.working_patterns = [
                            re.compile(p, re.IGNORECASE) for p in sd['working_indicators']
                        ]
                    if 'completion_indicators' in sd:
                        self.completion_patterns = [
                            re.compile(p, re.IGNORECASE) for p in sd['completion_indicators']
                        ]
        except Exception as e:
            print(f"[StateDetector] 配置加载失败: {e}")

    def detect(self, output: str) -> StateResult:
        """
        检测Claude当前状态

        Args:
            output: Claude输出内容

        Returns:
            StateResult: 状态检测结果
        """
        if not output or not output.strip():
            return StateResult(ClaudeState.IDLE, 1.0, "空输出")

        lines = output.strip().split('\n')
        last_lines = lines[-10:] if len(lines) > 10 else lines
        recent_text = '\n'.join(last_lines)

        # 检查工作状态
        working_matches = 0
        for pattern in self.working_patterns:
            if pattern.search(recent_text):
                working_matches += 1

        # 检查完成状态
        completion_matches = 0
        for pattern in self.completion_patterns:
            if pattern.search(recent_text):
                completion_matches += 1

        # 输出稳定性检测
        if output == self.last_output:
            self.stable_count += 1
        else:
            self.stable_count = 0
        self.last_output = output

        # 状态判断
        if working_matches > 0:
            confidence = min(0.5 + working_matches * 0.15, 0.95)
            return StateResult(ClaudeState.WORKING, confidence, f"检测到工作指示器: {working_matches}个")

        if completion_matches > 0:
            confidence = min(0.6 + completion_matches * 0.1, 0.95)
            return StateResult(ClaudeState.COMPLETED, confidence, f"检测到完成指示器: {completion_matches}个")

        # 输出稳定（连续相同）认为可能已完成
        if self.stable_count >= 3:
            return StateResult(ClaudeState.COMPLETED, 0.7, "输出已稳定")

        # 默认判断为工作中
        return StateResult(ClaudeState.WORKING, 0.5, "默认状态")

    def is_ready_for_speech(self, output: str) -> bool:
        """
        判断是否可以开始语音播报

        只在COMPLETED状态才返回True
        """
        result = self.detect(output)
        return result.state == ClaudeState.COMPLETED and result.confidence >= 0.6


# CLI测试
if __name__ == '__main__':
    import sys

    detector = StateDetector()

    test_cases = [
        "Thinking...",
        "任务已完成，请确认",
        "",
        "Reading file...\nAnalyzing data...\nProcessing",
        "验收通过，任务正式完成",
        "⠋ Working...",
    ]

    print("=== 状态检测测试 ===\n")
    for test in test_cases:
        result = detector.detect(test)
        print(f"输入: {test[:50]}..." if len(test) > 50 else f"输入: {test or '(空)'}")
        print(f"结果: {result.state.value} (置信度: {result.confidence:.2f})")
        print(f"原因: {result.reason}")
        print()