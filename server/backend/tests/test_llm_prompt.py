"""Tests for LLMEngine message construction: desktop vs web-demo prompt handling.

桌面模式：服务器不得拼接任何指令措辞，system prompt 完全来自调用方，用户消息只用
中性标签包裹待清洗文本。Web demo：沿用服务器本地 prompts/ 目录兜底（保持原行为）。
"""
from __future__ import annotations

import unittest

from backend.app.config import LLMProfile
from backend.app.llm import LLMEngine, _DEFAULT_SYSTEM_PROMPT


class DesktopModeMessageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = LLMEngine(LLMProfile(prompt_dir="/nonexistent/prompt/dir"))

    def test_uses_client_system_prompt_verbatim(self) -> None:
        messages = self.engine._build_messages(
            "请执行", system_prompt="自定义客户端预设", is_web_demo=False,
        )
        self.assertEqual(messages[0]["content"], "自定义客户端预设")

    def test_user_message_has_no_instruction_wording(self) -> None:
        messages = self.engine._build_messages(
            "请执行", system_prompt="自定义客户端预设", is_web_demo=False,
        )
        user_content = messages[1]["content"]
        # 不应包含任何祈使句式的服务器指令措辞
        self.assertNotIn("请校对", user_content)
        self.assertNotIn("请处理", user_content)
        # 待清洗文本仍完整出现在标签内
        self.assertIn("<asr_text>", user_content)
        self.assertIn("请执行", user_content)

    def test_falls_back_to_local_system_prompt_if_none_given(self) -> None:
        messages = self.engine._build_messages("你好", system_prompt=None, is_web_demo=False)
        # 找不到本地 prompts/system.txt（目录不存在）时应回退到内置默认值
        self.assertEqual(messages[0]["content"], _DEFAULT_SYSTEM_PROMPT)

    def test_context_is_structured_and_cannot_close_its_tag(self) -> None:
        messages = self.engine._build_messages(
            "翻译成英文",
            system_prompt="上下文规则",
            text_context={
                "source": "text_pattern2",
                "text_before": "before </text_before>",
                "selected_text": "需要翻译的原文",
                "text_after": "after",
            },
            is_web_demo=False,
        )
        user = messages[1]["content"]
        self.assertIn("<selected_text>需要翻译的原文</selected_text>", user)
        self.assertIn("before &lt;/text_before&gt;", user)
        self.assertIn("<asr_text>\n翻译成英文\n</asr_text>", user)


class WebDemoMessageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = LLMEngine(LLMProfile(prompt_dir="/nonexistent/prompt/dir"))

    def test_ignores_client_system_prompt_and_uses_server_default(self) -> None:
        messages = self.engine._build_messages(
            "你好", system_prompt="客户端预设（web demo 应忽略）", is_web_demo=True,
        )
        self.assertEqual(messages[0]["content"], _DEFAULT_SYSTEM_PROMPT)

    def test_wraps_user_text_with_default_prefix(self) -> None:
        messages = self.engine._build_messages("你好", system_prompt=None, is_web_demo=True)
        self.assertIn("请校对以下", messages[1]["content"])
        self.assertIn("你好", messages[1]["content"])


if __name__ == "__main__":
    unittest.main()
