#!/usr/bin/env python3
"""
内容提炼器 - 从Claude输出中提取适合语音播报的内容

功能：
1. 过滤代码块、工具调用、JSON等技术内容
2. 提取最终文字回答
3. 简化格式，生成适合语音播报的文本
"""

import re
from typing import List, Optional
from dataclasses import dataclass

@dataclass
class FilteredContent:
    text: str
    was_filtered: bool
    original_length: int
    filtered_length: int

class ContentSummarizer:
    """Claude输出内容提炼器"""

    # 需要跳过的模式
    SKIP_PATTERNS = [
        r'^```\w*',           # 代码块开始
        r'^```$',             # 代码块结束
        r'^\s*●',             # 工具调用开始
        r'^\s*○',             # 工具调用项
        r'^\s*\{.*\}\s*$',    # 单行JSON
        r'^\s*\[.*\]\s*$',    # 单行数组
        r'^\s*<[^>]+>',       # XML/HTML标签
        r'^\s*#.*$',          # Markdown标题（可选保留）
        r'[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏█░▓]',  # 进度动画
    ]

    # 关键词过滤
    SKIP_KEYWORDS = [
        'orbiting', 'scanning', 'analyzing', 'processing',
        'Error:', 'Traceback', 'File "', 'line ',
        'ModuleNotFoundError', 'ImportError',
    ]

    # tmux状态栏格式
    TMUX_STATUS_PATTERN = r'^\[.*\]\s*\d+:\[.*\].*$'

    # 文件权限格式
    FILE_LIST_PATTERN = r'^[-d][rwx-]{9}\s+.*'

    def __init__(self, max_length: int = 5000):
        self.max_length = max_length
        self._compile_patterns()

    def _compile_patterns(self):
        """编译正则表达式"""
        self.skip_regex = [re.compile(p) for p in self.SKIP_PATTERNS]
        self.tmux_regex = re.compile(self.TMUX_STATUS_PATTERN)
        self.file_regex = re.compile(self.FILE_LIST_PATTERN)

    def filter(self, text: str) -> FilteredContent:
        """
        过滤Claude输出，提取语音播报内容

        Args:
            text: 原始输出文本

        Returns:
            FilteredContent: 过滤后的内容
        """
        original_length = len(text)

        lines = text.split('\n')
        result_lines: List[str] = []
        in_code_block = False
        in_tool_block = False
        brace_count = 0

        for line in lines:
            trimmed = line.strip()

            # 代码块检测
            if trimmed.startswith('```'):
                in_code_block = not in_code_block
                continue
            if in_code_block:
                continue

            # 工具调用块检测
            if trimmed.startswith('●') or trimmed.startswith('○'):
                in_tool_block = True
                continue

            # JSON块检测（花括号计数）
            brace_count += trimmed.count('{')
            brace_count -= trimmed.count('}')
            if brace_count > 0:
                continue
            if in_tool_block and brace_count == 0 and not trimmed:
                in_tool_block = False
                continue

            # 跳过进度动画
            if any(c in trimmed for c in '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏█░▓'):
                continue

            # 跳过关键词
            if any(kw.lower() in trimmed.lower() for kw in self.SKIP_KEYWORDS):
                continue

            # 跳过tmux状态栏
            if self.tmux_regex.match(trimmed):
                continue

            # 跳过文件列表
            if self.file_regex.match(trimmed):
                continue

            # 跳过命令提示符
            if re.match(r'^[#$>~]\s*\w+', trimmed):
                continue

            # 空行处理（连续空行合并）
            if not trimmed:
                if result_lines and result_lines[-1]:
                    result_lines.append('')
                continue

            # 保留有效内容
            result_lines.append(trimmed)

        # 合并结果
        result = '\n'.join(result_lines)

        # 清理多余空行
        result = re.sub(r'\n{3,}', '\n\n', result)

        # 长度限制
        if len(result) > self.max_length:
            result = result[:self.max_length] + '...[内容已截断]'

        return FilteredContent(
            text=result,
            was_filtered=len(result) != original_length,
            original_length=original_length,
            filtered_length=len(result)
        )

    def extract_summary(self, text: str) -> str:
        """
        提取摘要内容

        优先提取：
        1. 【报告】或【周公旦报告】格式的内容
        2. 任务完成相关的陈述
        3. 最终结论
        """
        filtered = self.filter(text)

        # 提取报告块
        report_match = re.search(r'【[^】]+报告】(.+?)(?=【|$)', filtered.text, re.DOTALL)
        if report_match:
            return report_match.group(1).strip()

        # 提取任务完成相关
        completion_match = re.search(r'(任务.*完成[^。\n]*[。\n])', filtered.text)
        if completion_match:
            return completion_match.group(1).strip()

        # 返回过滤后的内容
        return filtered.text


# CLI测试
if __name__ == '__main__':
    summarizer = ContentSummarizer()

    test_output = """
【周公旦报告】

任务：水质报告审核

执行情况：完成

产出内容：
- /root/workspace/report/0270-0292/待确认问题清单.txt

```
这是代码块
应该被过滤
```

● Tool call...
{"json": "data"}

请求太公望殿下验收。
"""

    print("=== 内容提炼测试 ===\n")
    print("原始输出:")
    print(test_output)
    print("\n" + "="*50 + "\n")

    filtered = summarizer.filter(test_output)
    print(f"过滤后内容 (原{filtered.original_length}字 → {filtered.filtered_length}字):")
    print(filtered.text)
    print("\n" + "="*50 + "\n")

    summary = summarizer.extract_summary(test_output)
    print("提取摘要:")
    print(summary)