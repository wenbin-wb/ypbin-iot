#!/usr/bin/env python3
"""回归用例：`tools/rotate-*.py` 的 Nacos 口令读取与「口令不进 argv」。

运行（纯标准库，无第三方依赖）：
    python3 tools/test_rotate_nacos_password.py -v
    python3 -m unittest discover -s tools -p 'test_*.py'

覆盖 2026-09-26 修掉的三类缺陷，每条都以「修前必红」的可复现方式断言：

1. `_nacos_password()` 自我递归 —— 原实现在环境变量存在时直接调用自身；
   只要调用方 export 了 NACOS_ADMIN_PASSWORD（回滚脚本正要求这么做）就 RecursionError。
   回归：`test_env_password_is_returned_without_recursion`（修前抛 RecursionError）+ 结构断言。
2. 口令经 curl 的 `--data-urlencode "password=<值>"` 进 argv（宿主 `ps`、`docker events`
   的 exec 属性都能读到）。回归：`test_password_never_goes_into_argv`。
3. 生成「一键回滚脚本」的 f-string 模板里写了**单层**花括号的 shell 占位符
   `${NACOS_ADMIN_USERNAME:-nacos}` —— 在 f-string 里那是「表达式 + 格式说明」，
   运行期抛 NameError / 非法格式说明，即回滚物根本生成不出来；且它被单引号包着，
   即使渲染出来也不会被 shell 展开。回归：`test_rollback_templates_*`（结构断言 + 渲染期抓格式说明）。
"""
import ast
import importlib.util
import io
import os
import re
import unittest
from unittest import mock
from unittest.mock import MagicMock

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = [
    os.path.join(HERE, "rotate-secret.py"),
    os.path.join(HERE, "rotate-internal-token.py"),
    os.path.join(HERE, "fix-access-trusted-source.py"),
]
PROBE = "probe-value-not-a-secret-0123456789"
# shell 的「默认值」占位符 `${VAR:-def}` 被 f-string 吞掉后，留下的格式说明形如 `-def`。
# 合法的 Python 格式说明不会以「- 后接字母」开头。
SHELL_DEFAULT_SPEC = re.compile(r"^-[A-Za-z_]")


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def load_module(path):
    name = os.path.basename(path)[:-3].replace("-", "_")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class _Stub(MagicMock):
    """f-string 渲染用哑值：属性访问/下标/调用/格式化都不炸，但把格式说明留档。

    为什么不用字符串：模板里有 `os.environ.get(...)`、`md5(...)`、`body['message']` 这类
    属性访问/调用/下标（字符串会 AttributeError/TypeError），还会遇到 `{c:<16}` 这类**合法**
    格式说明（裸 MagicMock 会 TypeError）。继承 MagicMock 拿到全部魔术方法，只把 __format__
    换成「记录 + 返回常量」，于是唯一会被抓出来的就是「被 f-string 吞掉的 shell 占位符」。
    """

    SPECS = []

    def __format__(self, spec):
        _Stub.SPECS.append(spec)
        return "STUB"


def render_joined_str(path):
    """把文件里所有 f-string 用哑值渲染一遍，返回 (渲染结果列表, 收集到的格式说明列表)。

    - 未定义的表达式名 ⇒ NameError（修前的单层 `${VAR:-def}` 会走到这里）；
    - 非法格式说明 ⇒ 被记录进 SPECS，由用例断言其形状。
    """
    source = read(path)
    tree = ast.parse(source)
    _Stub.SPECS = []
    rendered = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.JoinedStr):
            continue
        names = {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}
        namespace = {name: _Stub() for name in names}
        code = compile(ast.Expression(node), "<fstring:{}>".format(os.path.basename(path)), "eval")
        rendered.append(eval(code, {"__builtins__": {}}, namespace))
    return rendered, list(_Stub.SPECS)


class NacosPasswordRegressionTest(unittest.TestCase):
    def test_env_password_is_returned_without_recursion(self):
        """缺陷 1：环境变量里有值时必须直接返回，不得自我递归（修前 RecursionError）。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                module = load_module(path)
                with mock.patch.dict(os.environ, {"NACOS_ADMIN_PASSWORD": PROBE}):
                    self.assertEqual(PROBE, module._nacos_password())

    def test_no_self_call_left_in_source(self):
        """缺陷 1 的结构兜底：`_nacos_password` 体内不得再出现自我调用。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                source = read(path)
                body = source.split("def _nacos_password():", 1)[1].split("\ndef ", 1)[0]
                self.assertNotIn("return _nacos_password()", body)

    def test_password_never_goes_into_argv(self):
        """缺陷 2：口令必须走 `--data-urlencode password@-`（stdin），不得拼进 argv。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                source = read(path)
                self.assertIn("password@-", source)
                self.assertNotIn('"password=" +', source)
                self.assertNotIn('--data-urlencode", "password=', source)
                self.assertNotIn("password=$NACOS_ADMIN_PASSWORD", source)

    def test_rollback_templates_render(self):
        """缺陷 3：文件内所有 f-string 都必须能渲染（未定义名 ⇒ 抛 NameError）。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                rendered, _ = render_joined_str(path)
                self.assertTrue(rendered, "没有扫到任何 f-string，用例失去意义")

    def test_no_shell_default_swallowed_by_fstring(self):
        """缺陷 3：f-string 里不得出现「被吞掉的 shell 默认值占位符」（格式说明形如 `-def`）。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                _, specs = render_joined_str(path)
                bad = [s for s in specs if SHELL_DEFAULT_SPEC.match(s)]
                self.assertEqual(
                    [], bad,
                    "f-string 里出现 shell 默认值占位符（${VAR:-def} 写成了单层花括号）: %r" % (bad,))

    def test_rollback_templates_use_escaped_braces_and_stdin(self):
        """缺陷 3：回滚模板的 shell 占位符必须写成 `${{...}}`，口令必须走 stdin。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                source = read(path)
                if "rollback" not in source:
                    continue
                self.assertNotIn("${NACOS_ADMIN_USERNAME:-nacos}", source)
                self.assertIn("${{NACOS_ADMIN_USERNAME:-nacos}}", source)
                self.assertTrue("'password@-'" in source or '"password@-"' in source,
                                "回滚模板里没有 stdin 形态的口令投递")
                self.assertIn('if [ -z "${{NACOS_ADMIN_PASSWORD:-}}" ]', source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
