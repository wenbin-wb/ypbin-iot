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
import contextlib
import importlib.util
import io
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
from unittest.mock import MagicMock

HERE = os.path.dirname(os.path.abspath(__file__))
FIX_ACCESS = os.path.join(HERE, "fix-access-trusted-source.py")
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
                self.assertFalse("return _nacos_password()" in body,
                                 "%s 的 _nacos_password 仍在自我调用" % os.path.basename(path))

    def test_password_never_goes_into_argv(self):
        """缺陷 2：口令必须走 `--data-urlencode password@-`（stdin），不得拼进 argv。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                source = read(path)
                self.assertIn("password@-", source)
                for bad in ('"password=" +', '--data-urlencode", "password=', "password=$NACOS_ADMIN_PASSWORD"):
                    self.assertFalse(bad in source,
                                     "%s 仍把口令拼进 argv：%r" % (os.path.basename(path), bad))

    def test_no_shell_default_swallowed_by_fstring(self):
        """缺陷 3a：f-string 里不得出现「被吞掉的 shell 默认值占位符」（格式说明形如 `-def`）。

        说明（复核纠正）：**不能**靠「渲染一遍不抛异常」来证明这条 —— 渲染 harness 会给所有
        ast.Name 注入哑值，修前也能渲染成功（原用例因此恒真无效）。真正能咬人的是「格式说明形状」。
        """
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                _, specs = render_joined_str(path)
                bad = [s for s in specs if SHELL_DEFAULT_SPEC.match(s)]
                self.assertEqual(
                    [], bad,
                    "f-string 里出现 shell 默认值占位符（${VAR:-def} 写成了单层花括号）: %r" % (bad,))

    def test_rendered_rollback_args_are_expandable(self):
        """缺陷 3b：把渲染后的 rollback 里的 curl 参数**真的交给 bash 展开**，断言取到用户名。

        复核实测的漏网点：单引号 `'username=${NACOS_ADMIN_USERNAME:-nacos}'` 在 shell 里**不展开**，
        服务端收到字面占位符 ⇒ 回滚脚本在「已还原 .env」之后登录失败、`exit 3` = **半回滚**。
        只看源码形态抓不到这件事，必须做「渲染 → 交给 bash → 看展开结果」。
        """
        bash = shutil.which("bash")
        if not bash:
            self.skipTest("无 bash，跳过展开验证")
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                rendered, _ = render_joined_str(path)
                text = "\n".join(rendered)
                # 只取**登录**那两个参数：配置 POST 段里还有 `dataId=$c.yaml` 这类
                # 依赖循环变量的参数，交给 bash 展开会得到「c: unbound variable」的假红。
                args = [a for a in re.findall(r"--data-urlencode\s+(\"[^\"]*\"|'[^']*')", text)
                        if "username=" in a or "password" in a]
                if not args:
                    continue  # 该文件没有 HTTP 登录片段
                script = "set -u; printf '%s\\n' " + " ".join(args)
                env = {k: v for k, v in os.environ.items() if k != "NACOS_ADMIN_USERNAME"}
                out = subprocess.run([bash, "-c", script], capture_output=True, text=True, env=env)
                self.assertEqual("", out.stderr, "展开时 shell 报错（例如 unbound variable）")
                lines = out.stdout.split()
                self.assertIn("username=nacos", lines,
                              "渲染后的 username 参数没有展开（很可能被单引号包住）: %r" % (lines,))
                self.assertIn("password@-", lines)

    def test_generated_rollback_script_is_syntactically_valid(self):
        """缺陷 3c：渲染出的**回滚脚本整体**必须通过 `bash -n`。

        只取「以 shebang 开头」的那段 f-string（复核者提醒：不加这个过滤会把散文类 f-string
        也喂给 bash，得到假红）。三段都是「整脚本一个 f-string」，故各命中一次。
        这条能抓住转义/引号层面的破坏（例如 `${{…}}` 写错、反斜杠少一层），是本轮实际漏网的那类问题。
        注：harness 用哑值替换集合变量，循环会渲染成 `for c in ; do`——空单词表的 for 在 bash 里**合法**
        （rc=0），所以不会假红。
        """
        bash = shutil.which("bash")
        if not bash:
            self.skipTest("无 bash，跳过语法校验")
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                rendered, _ = render_joined_str(path)
                scripts = [r for r in rendered if r.lstrip().startswith("#!")]
                self.assertTrue(scripts, "%s 没扫到回滚脚本段" % os.path.basename(path))
                for script in scripts:
                    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False,
                                                     encoding="utf-8") as fh:
                        fh.write(script)
                        tmp = fh.name
                    try:
                        out = subprocess.run([bash, "-n", tmp], capture_output=True, text=True)
                        self.assertEqual(0, out.returncode,
                                         "生成的回滚脚本语法不合法：%s" % out.stderr[:200])
                    finally:
                        os.unlink(tmp)

    def test_rollback_templates_use_escaped_braces_and_stdin(self):
        """缺陷 3：回滚模板的 shell 占位符必须写成 `${{...}}`，口令必须走 stdin。"""
        for path in TOOLS:
            with self.subTest(tool=os.path.basename(path)):
                source = read(path)
                if "rollback" not in source:
                    continue
                name = os.path.basename(path)
                self.assertFalse("${NACOS_ADMIN_USERNAME:-nacos}" in source,
                                 "%s 的 f-string 里仍有单层花括号占位符" % name)
                self.assertTrue("${{NACOS_ADMIN_USERNAME:-nacos}}" in source, name)
                self.assertTrue("'password@-'" in source or '"password@-"' in source,
                                "%s 回滚模板里没有 stdin 形态的口令投递" % name)
                self.assertFalse("--data-urlencode 'username=" in source,
                                 "%s 回滚模板的 username 被单引号包住 ⇒ shell 不展开 ⇒ 回滚半途 exit 3" % name)
                self.assertTrue('if [ -z "${{NACOS_ADMIN_PASSWORD:-}}" ]' in source, name)


class CredentialSeparationTest(unittest.TestCase):
    """两个凭据必须是**两个名字/两个值**：`GATEWAY_SIGN_TOKEN`（要写进配置）与 Nacos accessToken（JWT）。

    回归的缺陷（PR #64 引入、独立复核者桩化实证）：`fix-access-trusted-source.py` 的 main() 里
    两个凭据都叫 `token`，`token = login()` 覆盖了 `GATEWAY_SIGN_TOKEN` ⇒ `build_block(token)`
    会把 **JWT 写进 ypbin-access.yaml 的 cloud.feign.trusted-source-token**；而其后的
    `assert got == token` 变成**自比自、恒真**，还会打印「与 .env 的 GATEWAY_SIGN_TOKEN 一致」的假绿。
    ⇒ 本用例**不依赖工具自己的断言**，直接检查「传给 build_block 的实参」与「写出的配置内容」。
    """

    # 必须满足工具自身后续断言：恰好两个 `${...}` 占位符 + 唯一锚点行 + 尚无该键
    LIVE = (
        "server:\n"
        "  port: 18086\n"
        "spring:\n"
        "  cloud:\n"
        "    nacos:\n"
        "      server-addr: ${NACOS_ADDR:localhost:8848}\n"
        "  autoconfigure:\n"
        "    exclude:\n"
        "      - a.b.C\n"
        "  # access 是下游服务\n"
        "ypbin:\n"
        "  access:\n"
        "    node-id: ${ACCESS_NODE_ID:access-1}\n"
    )

    def _run_main_with_sentinels(self):
        mod = load_module(FIX_ACCESS)
        gst_sentinel = "GST_SENTINEL_should_be_written"
        jwt_sentinel = "JWT_SENTINEL_nacos_access_token"
        seen = {}
        real_build = mod.build_block

        def spy_build(value):
            seen["build_block_arg"] = value
            return real_build(value)

        handles = []
        real_open = open

        def fake_open(path, mode="r", *a, **k):
            handle = mock.mock_open()(path, mode, *a, **k)
            handle.name = str(path)
            handles.append(handle)
            return handle

        with mock.patch.dict(os.environ, {"GATEWAY_SIGN_TOKEN": gst_sentinel,
                                          "NACOS_ADMIN_PASSWORD": "probe-not-a-secret"}):
            with mock.patch.object(mod, "login", return_value=jwt_sentinel), \
                 mock.patch.object(mod, "dump_live", return_value=self.LIVE), \
                 mock.patch.object(mod, "build_block", spy_build), \
                 mock.patch.object(mod, "APPLY", False), \
                 mock.patch.object(mod.os, "chmod"), \
                 mock.patch("builtins.open", fake_open), \
                 contextlib.redirect_stdout(io.StringIO()), \
                 contextlib.redirect_stderr(io.StringIO()):
                mod.main()
        written = "".join(str(c.args[0]) for h in handles for c in h.write.call_args_list if c.args)
        return gst_sentinel, jwt_sentinel, seen, written

    def test_build_block_receives_gateway_sign_token_not_jwt(self):
        gst, jwt, seen, _ = self._run_main_with_sentinels()
        self.assertIn("build_block_arg", seen, "没走到 build_block，用例失去意义")
        self.assertEqual(gst, seen["build_block_arg"],
                         "传给 build_block 的不是 GATEWAY_SIGN_TOKEN（很可能被 login() 的 JWT 覆盖）")
        self.assertNotEqual(jwt, seen["build_block_arg"])

    def test_written_config_contains_gateway_token_only(self):
        gst, jwt, _, written = self._run_main_with_sentinels()
        self.assertTrue(written, "没抓到写文件调用，用例失去意义")
        self.assertIn(gst, written)
        self.assertNotIn(jwt, written, "Nacos JWT 被写进了要 POST 的配置内容")


if __name__ == "__main__":
    unittest.main(verbosity=2)
