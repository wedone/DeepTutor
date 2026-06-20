"""PartnerManager config persistence, merge semantics, and legacy migration."""

from __future__ import annotations

import dataclasses

import yaml

from deeptutor.services.partners.manager import PartnerConfig, PartnerManager
from deeptutor.services.partners.workspace import DEFAULT_SOUL, read_soul


def _mgr() -> PartnerManager:
    return PartnerManager()


class TestConfigRoundTrip:
    def test_save_and_load(self, partners_root):
        mgr = _mgr()
        config = PartnerConfig(
            name="Ada",
            description="study partner",
            channels={"telegram": {"enabled": True, "token": "t"}},
            llm_selection={"profile_id": "p", "model_id": "m"},
            language="zh",
            emoji="🦊",
            color="#aabbcc",
            soul_origin={"type": "library", "id": "math-tutor"},
            enabled_tools=["web_search"],
            builtin_tools=["rag", "read_memory"],
            mcp_tools=[],
        )
        mgr.save_config("ada", config)
        loaded = mgr.load_config("ada")
        assert loaded is not None
        assert dataclasses.asdict(loaded) == dataclasses.asdict(config)

    def test_missing_returns_none(self, partners_root):
        assert _mgr().load_config("nope") is None

    def test_none_tool_fields_stay_none(self, partners_root):
        mgr = _mgr()
        mgr.save_config("p1", PartnerConfig(name="P1"))
        loaded = mgr.load_config("p1")
        assert loaded.enabled_tools is None
        assert loaded.builtin_tools is None
        assert loaded.mcp_tools is None


class TestMergeSemantics:
    def test_none_values_preserve_existing(self, partners_root):
        mgr = _mgr()
        mgr.save_config(
            "p1",
            PartnerConfig(name="Keep", description="keep me", enabled_tools=["rag"]),
        )
        merged = mgr.merge_config("p1", {"name": None, "description": None})
        assert merged.name == "Keep"
        assert merged.description == "keep me"
        assert merged.enabled_tools == ["rag"]

    def test_empty_values_are_intentional_clears(self, partners_root):
        mgr = _mgr()
        mgr.save_config("p1", PartnerConfig(name="Keep", description="old"))
        merged = mgr.merge_config("p1", {"description": "", "channels": {}})
        assert merged.description == ""
        assert merged.channels == {}

    def test_unknown_keys_ignored(self, partners_root):
        merged = _mgr().merge_config("new", {"bogus": 1, "name": "X"})
        assert merged.name == "X"
        assert not hasattr(merged, "bogus")

    def test_mergeable_fields_match_partnerconfig_fields(self):
        """Every config field must be mergeable via the API (anti-drift pin).

        ``owner_id`` is intentionally excluded — it's set at creation time
        and must not be overridable via merge_config.
        """
        field_names = {f.name for f in dataclasses.fields(PartnerConfig)} - {"owner_id"}
        assert set(PartnerManager._MERGEABLE_FIELDS) == field_names


class TestAutoStart:
    def test_new_partner_defaults_to_auto_start(self, partners_root):
        mgr = _mgr()
        mgr.save_config("p1", PartnerConfig(name="P1"))
        assert mgr._load_auto_start("p1", default=False) is True

    def test_routine_save_preserves_disabled_intent(self, partners_root):
        mgr = _mgr()
        mgr.save_config("p1", PartnerConfig(name="P1"), auto_start=False)
        # Routine save (auto_start omitted) must not silently flip it back on.
        mgr.save_config("p1", PartnerConfig(name="P1 renamed"))
        assert mgr._load_auto_start("p1", default=True) is False


class TestWorkspaceSeeding:
    def test_ensure_dirs_seeds_default_soul(self, partners_root):
        mgr = _mgr()
        mgr._ensure_partner_dirs("p1")
        assert read_soul("p1") == DEFAULT_SOUL
        ws = partners_root / "p1" / "workspace"
        assert (ws / "user" / "workspace").is_dir()
        assert (ws / "knowledge_bases").is_dir()

    def test_existing_soul_not_overwritten(self, partners_root):
        from deeptutor.services.partners.workspace import write_soul

        mgr = _mgr()
        mgr._ensure_partner_dirs("p1")
        write_soul("p1", "# Custom")
        mgr._ensure_partner_dirs("p1")
        assert read_soul("p1") == "# Custom"


class TestLegacyTutorBotMigration:
    def _seed_legacy_bot(self, admin_root, bot_id="old-bot", **overrides):
        legacy = admin_root / "tutorbot" / bot_id
        legacy.mkdir(parents=True)
        data = {
            "name": "Old Bot",
            "description": "from tutorbot",
            "persona": "# Soul\nLegacy persona text",
            "channels": {"telegram": {"enabled": True, "token": "tok"}},
            "llm_selection": {"profile_id": "p", "model_id": "m"},
            "auto_start": True,
            **overrides,
        }
        (legacy / "config.yaml").write_text(yaml.dump(data), encoding="utf-8")
        sessions = legacy / "workspace" / "sessions"
        sessions.mkdir(parents=True)
        (sessions / "telegram_1.jsonl").write_text(
            '{"role": "user", "content": "hi", "timestamp": "2026-01-01T00:00:00"}\n',
            encoding="utf-8",
        )
        return legacy

    def test_migrates_config_soul_and_sessions(self, partners_root):
        admin_root = partners_root.parent
        self._seed_legacy_bot(admin_root)

        mgr = _mgr()
        ids = mgr._discover_partner_ids()
        assert "old-bot" in ids

        cfg = mgr.load_config("old-bot")
        assert cfg.name == "Old Bot"
        assert cfg.channels["telegram"]["token"] == "tok"
        assert cfg.llm_selection == {"profile_id": "p", "model_id": "m"}
        assert cfg.soul_origin == {"type": "tutorbot", "id": "old-bot"}
        assert read_soul("old-bot") == "# Soul\nLegacy persona text"
        assert mgr._load_auto_start("old-bot", default=False) is True

        history = mgr.get_history("old-bot")
        assert history and history[0]["content"] == "hi"

    def test_migration_is_idempotent_and_non_destructive(self, partners_root):
        admin_root = partners_root.parent
        legacy = self._seed_legacy_bot(admin_root)

        mgr = _mgr()
        mgr._discover_partner_ids()
        # Tweak the migrated partner, then re-discover with a fresh manager.
        from deeptutor.services.partners.workspace import write_soul

        write_soul("old-bot", "# Edited after migration")
        mgr2 = _mgr()
        mgr2._discover_partner_ids()
        assert read_soul("old-bot") == "# Edited after migration"
        # Legacy tree untouched.
        assert (legacy / "config.yaml").exists()


class TestSoulLibraryRefresh:
    """Untouched old-seed library entries upgrade in place; user souls survive."""

    _TUTORBOT_ENTRY = {
        "id": "default-tutorbot",
        "name": "Default TutorBot",
        "content": "# Soul\n\nI am TutorBot, a personal learning companion.\n\n"
        "## Personality\n\n- Helpful and friendly\n- Clear, encouraging, and patient\n"
        "- Adapts explanations to the user's level\n\n"
        "## Values\n\n- Accuracy over speed\n- User privacy and safety\n- Transparency in actions",
    }

    def test_tutorbot_era_library_is_upgraded(self, partners_root):
        mgr = _mgr()
        mgr._save_souls([dict(self._TUTORBOT_ENTRY)])
        souls = mgr.list_souls()
        assert [s["id"] for s in souls] == ["companion"]
        assert "tutorbot" not in yaml.dump(souls).lower()

    def test_user_souls_pass_through_verbatim(self, partners_root):
        mgr = _mgr()
        mine = {"id": "my-bot", "name": "Mine", "content": "I miss TutorBot"}
        edited_seed = {"id": "math-tutor", "name": "Math Tutor", "content": "# My own text"}
        mgr._save_souls([dict(self._TUTORBOT_ENTRY), mine, edited_seed])
        souls = mgr.list_souls()
        assert souls == [
            {"id": "companion", "name": "Learning Companion", "content": souls[0]["content"]},
            mine,
            edited_seed,
        ]

    def test_refresh_is_idempotent(self, partners_root):
        mgr = _mgr()
        mgr._save_souls([dict(self._TUTORBOT_ENTRY)])
        first = mgr.list_souls()
        assert mgr.list_souls() == first


class TestOwnerIsolation:
    """多用户隔离：owner_id 的持久化、反查与路径解析。"""

    def test_config_roundtrip_with_owner_id(self, partners_root):
        """保存带 owner_id 的 config，加载后 owner_id 正确。"""
        mgr = _mgr()
        config = PartnerConfig(name="Ada", owner_id="u1")
        mgr.save_config("ada", config, owner_id="u1")
        loaded = mgr.load_config("ada", owner_id="u1")
        assert loaded is not None
        assert loaded.owner_id == "u1"

    def test_legacy_config_defaults_to_empty_owner(self, partners_root):
        """旧 config（无 owner_id 字段）加载后 owner_id == ''。"""
        mgr = _mgr()
        # 手写一个不带 owner_id 的旧格式 config
        partner_dir = partners_root / "legacy"
        partner_dir.mkdir(parents=True)
        (partner_dir / "config.yaml").write_text(
            yaml.dump({"name": "Legacy", "channels": {}}), encoding="utf-8"
        )
        loaded = mgr.load_config("legacy")
        assert loaded is not None
        assert loaded.owner_id == ""

    def test_mergeable_fields_excludes_owner_id(self):
        """owner_id 不参与 merge_config（由创建时确定，不可通过 API 修改）。"""
        assert "owner_id" not in PartnerManager._MERGEABLE_FIELDS

    def test_discover_partner_ids_scans_multiple_users(
        self, partners_root, user_partners_root
    ):
        """admin 和用户目录下的 partner 都能被 _discover_partner_ids 发现。"""
        mgr = _mgr()
        # admin 目录下创建 partner
        mgr.save_config("admin-bot", PartnerConfig(name="Admin Bot", owner_id=""))
        # 用户 u1 目录下创建 partner
        u1_root = user_partners_root("u1")
        (u1_root / "user-bot").mkdir()
        (u1_root / "user-bot" / "config.yaml").write_text(
            yaml.dump({"name": "User Bot", "owner_id": "u1"}), encoding="utf-8"
        )
        ids = mgr._discover_partner_ids()
        assert "admin-bot" in ids
        assert "user-bot" in ids

    def test_resolve_owner_id_caches_result(self, user_partners_root):
        """resolve_owner_for_partner 返回正确 owner，且结果被缓存。"""
        from deeptutor.partners.config.paths import (
            _owner_cache,
            resolve_owner_for_partner,
        )

        u1_root = user_partners_root("u1")
        (u1_root / "p1").mkdir()
        (u1_root / "p1" / "config.yaml").write_text(
            yaml.dump({"name": "P1", "owner_id": "u1"}), encoding="utf-8"
        )
        # 第一次调用：扫描磁盘并缓存
        assert resolve_owner_for_partner("p1") == "u1"
        # 验证缓存已写入
        assert _owner_cache.get("p1") == "u1"
        # 第二次调用：应返回相同结果（走缓存）
        assert resolve_owner_for_partner("p1") == "u1"

    def test_resolve_owner_id_admin_fallback(self, partners_root):
        """admin 目录下的 partner，resolve_owner_for_partner 返回空串。"""
        from deeptutor.partners.config.paths import resolve_owner_for_partner

        mgr = _mgr()
        mgr.save_config("admin-bot", PartnerConfig(name="Admin Bot", owner_id=""))
        assert resolve_owner_for_partner("admin-bot") == ""

    def test_single_user_mode_path_unchanged(self, partners_root):
        """owner_id='' 时 get_partner_dir 与单机模式路径一致。"""
        from deeptutor.partners.config.paths import _base_dir, get_partner_dir

        # owner_id='' 等价于不传 owner_id
        assert get_partner_dir("p1", owner_id="") == get_partner_dir("p1")
        # 且等于旧 _base_dir() / "p1"
        assert get_partner_dir("p1", owner_id="") == _base_dir() / "p1"
