"""API surface tests for /api/v1/partners (create / config / soul / assets)."""

from __future__ import annotations

import base64
import json
from pathlib import Path
from unittest.mock import patch

import pytest
import yaml

try:
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
except Exception:  # pragma: no cover
    FastAPI = None
    TestClient = None

pytestmark = pytest.mark.skipif(
    FastAPI is None or TestClient is None, reason="fastapi not installed"
)


@pytest.fixture
def isolated_root(tmp_path, monkeypatch) -> Path:
    from deeptutor.multi_user import paths

    project_root = tmp_path
    admin_root = (project_root / "data").resolve()
    monkeypatch.setattr(paths, "PROJECT_ROOT", project_root)
    monkeypatch.setattr(paths, "ADMIN_WORKSPACE_ROOT", admin_root)
    monkeypatch.setattr(paths, "USERS_ROOT", admin_root / "users")
    monkeypatch.setattr(paths, "SYSTEM_ROOT", admin_root / "system")
    monkeypatch.setattr(paths, "_path_services", {})
    admin_root.mkdir(parents=True, exist_ok=True)
    return admin_root


@pytest.fixture
def client(isolated_root, monkeypatch) -> TestClient:
    import deeptutor.api.routers.partners as partners_router_mod
    from deeptutor.services.partners.manager import PartnerManager

    # Fresh manager per test so the module-level singleton can't leak
    # tmp-path state across tests.
    mgr = PartnerManager()
    monkeypatch.setattr(partners_router_mod, "get_partner_manager", lambda: mgr)
    partners_router_mod._start_locks.clear()

    app = FastAPI()
    app.include_router(partners_router_mod.router, prefix="/api/v1/partners")
    return TestClient(app)


def _create(client: TestClient, **overrides):
    payload = {
        "name": "Ada",
        "description": "study partner",
        "soul": {"source": "custom", "content": "# Soul\nBe rigorous."},
        "start": False,
        **overrides,
    }
    return client.post("/api/v1/partners", json=payload)


class TestCreate:
    def test_create_returns_masked_config(self, client):
        res = _create(
            client,
            channels={"telegram": {"enabled": True, "token": "123:ABC"}},
            enabled_tools=["web_search"],
            mcp_tools=[],
        )
        assert res.status_code == 200
        body = res.json()
        assert body["partner_id"] == "ada"
        assert body["channels"]["telegram"]["token"] == "***"
        assert body["enabled_tools"] == ["web_search"]
        assert body["mcp_tools"] == []
        assert body["soul_origin"] == {"type": "custom", "id": ""}
        assert body["provisioning"]["errors"] == []

    def test_duplicate_id_auto_suffix(self, client):
        assert _create(client).status_code == 200
        # 同用户内创建同名 partner 自动加后缀，不再返回 409
        res = _create(client)
        assert res.status_code == 200
        assert res.json()["partner_id"] == "ada-2"

    def test_top_level_delivery_flags_rejected(self, client):
        res = _create(client, channels={"send_progress": False})
        assert res.status_code == 422

    def test_create_from_library_soul(self, client):
        res = _create(
            client,
            partner_id="mathy",
            soul={"source": "library", "id": "math-tutor"},
        )
        assert res.status_code == 200
        soul = client.get("/api/v1/partners/mathy/soul").json()
        assert "math tutor" in soul["content"].lower()

    def test_create_with_unknown_library_soul_404(self, client):
        res = _create(client, soul={"source": "library", "id": "ghost"})
        assert res.status_code == 404


class TestConfigAndSoul:
    def test_get_masks_secrets_by_default(self, client):
        _create(client, channels={"telegram": {"enabled": True, "token": "raw"}})
        body = client.get("/api/v1/partners/ada").json()
        assert body["channels"]["telegram"]["token"] == "***"
        body = client.get("/api/v1/partners/ada?include_secrets=true").json()
        assert body["channels"]["telegram"]["token"] == "raw"

    def test_patch_updates_tools_and_clears(self, client):
        _create(client, enabled_tools=["web_search", "paper_search"])
        res = client.patch(
            "/api/v1/partners/ada",
            json={"enabled_tools": [], "mcp_tools": ["mcp_x_y"]},
        )
        assert res.status_code == 200
        body = client.get("/api/v1/partners/ada").json()
        assert body["enabled_tools"] == []
        assert body["mcp_tools"] == ["mcp_x_y"]

    def test_avatar_roundtrip_and_validation(self, client):
        _create(client)
        avatar = "data:image/png;base64,iVBORw0KGgo="
        res = client.patch("/api/v1/partners/ada", json={"avatar": avatar})
        assert res.status_code == 200
        assert client.get("/api/v1/partners/ada").json()["avatar"] == avatar

        # Clearing works; junk and oversized payloads are rejected.
        assert client.patch("/api/v1/partners/ada", json={"avatar": ""}).status_code == 200
        assert client.get("/api/v1/partners/ada").json()["avatar"] == ""
        res = client.patch("/api/v1/partners/ada", json={"avatar": "https://evil.example/x.png"})
        assert res.status_code == 422
        res = client.patch(
            "/api/v1/partners/ada",
            json={"avatar": "data:image/png;base64," + "A" * 200_001},
        )
        assert res.status_code == 422

    def test_soul_roundtrip(self, client):
        _create(client)
        res = client.put("/api/v1/partners/ada/soul", json={"content": "# Soul\nUpdated."})
        assert res.status_code == 200
        assert client.get("/api/v1/partners/ada/soul").json()["content"] == "# Soul\nUpdated."

    def test_404_for_unknown_partner(self, client):
        assert client.get("/api/v1/partners/ghost").status_code == 404
        assert client.get("/api/v1/partners/ghost/soul").status_code == 404


class TestAssets:
    def _seed_skill(self, admin_root: Path, name="focus"):
        skill = admin_root / "user" / "workspace" / "skills" / name
        skill.mkdir(parents=True)
        (skill / "SKILL.md").write_text(
            f"---\nname: {name}\ndescription: d\n---\nBody", encoding="utf-8"
        )

    def test_add_list_remove_assets(self, client, isolated_root):
        self._seed_skill(isolated_root)
        _create(client)

        res = client.post("/api/v1/partners/ada/assets", json={"skills": ["focus"]})
        assert res.status_code == 200
        assert res.json()["copied"]["skills"] == ["focus"]
        assert [s["name"] for s in res.json()["assets"]["skills"]] == ["focus"]

        res = client.delete("/api/v1/partners/ada/assets/skill/focus")
        assert res.status_code == 200
        assert res.json()["assets"]["skills"] == []

    def test_unknown_asset_reported_in_errors(self, client):
        _create(client)
        res = client.post("/api/v1/partners/ada/assets", json={"skills": ["ghost"]})
        assert res.status_code == 200
        assert res.json()["errors"][0]["type"] == "skill"


class TestSoulLibraryEndpoints:
    def test_souls_crud(self, client):
        res = client.get("/api/v1/partners/souls")
        assert res.status_code == 200
        assert any(s["id"] == "math-tutor" for s in res.json())

        res = client.post(
            "/api/v1/partners/souls",
            json={"id": "custom-soul", "name": "Custom", "content": "# Soul"},
        )
        assert res.status_code == 200
        assert client.get("/api/v1/partners/souls/custom-soul").status_code == 200
        assert (
            client.put("/api/v1/partners/souls/custom-soul", json={"name": "Renamed"}).json()[
                "name"
            ]
            == "Renamed"
        )
        assert client.delete("/api/v1/partners/souls/custom-soul").status_code == 200
        assert client.get("/api/v1/partners/souls/custom-soul").status_code == 404

    def test_soul_sources_shape(self, client):
        body = client.get("/api/v1/partners/soul-sources").json()
        assert "library" in body and "personas" in body


class TestHistory:
    def test_history_reads_session_store(self, client, isolated_root):
        _create(client)
        sessions = isolated_root / "partners" / "ada" / "sessions"
        sessions.mkdir(parents=True, exist_ok=True)
        (sessions / "telegram_42.jsonl").write_text(
            json.dumps({"role": "user", "content": "hi", "timestamp": "2026-01-01T00:00:00"})
            + "\n",
            encoding="utf-8",
        )
        res = client.get("/api/v1/partners/ada/history")
        assert res.status_code == 200
        assert res.json()[0]["content"] == "hi"


class TestChatAttachments:
    def test_chat_does_not_auto_start_stopped_partner(self, client):
        assert _create(client, start=True).status_code == 200
        assert client.post("/api/v1/partners/ada/stop").status_code == 200

        res = client.post("/api/v1/partners/ada/chat", json={"content": "hello"})

        assert res.status_code == 409
        from deeptutor.core.i18n import t

        assert res.json()["detail"] == t("api.partner_stopped_start_required")

    def test_create_start_false_disables_auto_start(self, client, isolated_root):
        assert _create(client, start=False).status_code == 200

        data = yaml.safe_load(
            (isolated_root / "partners" / "ada" / "config.yaml").read_text(encoding="utf-8")
        )
        assert data["auto_start"] is False

    def test_materialize_partner_attachment_writes_partner_media(self, isolated_root):
        from deeptutor.api.routers.partners import (
            ChatAttachmentRequest,
            _materialize_partner_attachments,
        )

        paths = _materialize_partner_attachments(
            "ada",
            [
                ChatAttachmentRequest(
                    type="file",
                    filename="notes.txt",
                    base64=base64.b64encode(b"hello").decode("ascii"),
                    mime_type="text/plain",
                )
            ],
        )

        assert len(paths) == 1
        path = Path(paths[0])
        assert path.read_bytes() == b"hello"
        assert path.name.endswith("_notes.txt")
        assert path.parent == isolated_root / "partners" / "ada" / "media" / "web"


# ── Ownership 校验测试 ──────────────────────────────────────────


def _make_user(user_id: str, is_admin: bool = False):
    """构造一个 CurrentUser 对象用于 mock get_current_user。"""
    from deeptutor.multi_user.models import CurrentUser, UserScope
    from deeptutor.multi_user.paths import admin_scope, scope_for_user

    if is_admin:
        scope = admin_scope()
    else:
        scope = scope_for_user(user_id, is_admin=False)
    return CurrentUser(id=user_id, username=user_id, role="admin" if is_admin else "user", scope=scope)


class TestOwnership:
    """测试 _check_partner_owner 和 owner_id 自动填充。"""

    def test_create_fills_owner_id_for_admin(self, client, isolated_root):
        """admin 用户创建的 partner owner_id 为空串。"""
        admin_user = _make_user("local-admin", is_admin=True)
        with patch("deeptutor.api.routers.partners._check_partner_owner", return_value=""), \
             patch("deeptutor.multi_user.context.get_current_user", return_value=admin_user):
            res = _create(client)
        assert res.status_code == 200
        assert res.json()["owner_id"] == ""

    def test_create_fills_owner_id_for_normal_user(self, client, isolated_root):
        """普通用户创建的 partner owner_id 为用户 id。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        user = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = _create(client)
        assert res.status_code == 200
        assert res.json()["owner_id"] == "user1"
        invalidate_owner_cache()

    def test_admin_cannot_access_user_partner(self, client, isolated_root):
        """admin 不能操作属于普通用户的 partner。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        # 先以普通用户创建 partner
        user = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = _create(client)
        assert res.status_code == 200
        invalidate_owner_cache()

        # admin 尝试访问该 partner → 403
        admin_user = _make_user("local-admin", is_admin=True)
        with patch("deeptutor.multi_user.context.get_current_user", return_value=admin_user):
            res = client.get("/api/v1/partners/ada")
        assert res.status_code == 403
        invalidate_owner_cache()

    def test_user_cannot_access_other_user_partner(self, client, isolated_root):
        """普通用户不能操作属于其他用户的 partner。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        # 以 user1 创建 partner
        user1 = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user1):
            res = _create(client)
        assert res.status_code == 200
        invalidate_owner_cache()

        # user2 尝试访问 → 403
        user2 = _make_user("user2")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user2):
            res = client.get("/api/v1/partners/ada")
        assert res.status_code == 403
        invalidate_owner_cache()

    def test_user_can_access_own_partner(self, client, isolated_root):
        """普通用户可以访问自己的 partner。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        user = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = _create(client)
        assert res.status_code == 200
        invalidate_owner_cache()

        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = client.get("/api/v1/partners/ada")
        assert res.status_code == 200
        assert res.json()["owner_id"] == "user1"
        invalidate_owner_cache()

    def test_list_partners_filters_by_owner(self, client, isolated_root):
        """list_partners 只返回当前用户的 partner。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        # admin 创建 partner
        admin_user = _make_user("local-admin", is_admin=True)
        with patch("deeptutor.multi_user.context.get_current_user", return_value=admin_user):
            _create(client, name="Admin Partner")
            invalidate_owner_cache()

        # user1 创建 partner
        user1 = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user1):
            _create(client, name="User1 Partner")
            invalidate_owner_cache()

        # admin 列表只看到 owner_id="" 的 partner
        with patch("deeptutor.multi_user.context.get_current_user", return_value=admin_user):
            res = client.get("/api/v1/partners")
        assert res.status_code == 200
        names = [p["name"] for p in res.json()]
        assert "Admin Partner" in names
        assert "User1 Partner" not in names

        # user1 列表只看到自己的 partner
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user1):
            res = client.get("/api/v1/partners")
        assert res.status_code == 200
        names = [p["name"] for p in res.json()]
        assert "User1 Partner" in names
        assert "Admin Partner" not in names
        invalidate_owner_cache()

    def test_different_users_can_create_same_name_partner(self, client, isolated_root):
        """不同用户可以创建同名 partner。"""
        from deeptutor.partners.config.paths import invalidate_owner_cache

        # admin 创建 ada
        admin_user = _make_user("local-admin", is_admin=True)
        with patch("deeptutor.multi_user.context.get_current_user", return_value=admin_user):
            res = _create(client)
        assert res.status_code == 200
        assert res.json()["partner_id"] == "ada"
        invalidate_owner_cache()

        # user1 也可以创建 ada（不同 owner scope）
        user1 = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user1):
            res = _create(client)
        assert res.status_code == 200
        assert res.json()["partner_id"] == "ada"
        assert res.json()["owner_id"] == "user1"
        invalidate_owner_cache()

    def test_soul_library_write_requires_admin(self, client, isolated_root):
        """灵魂模板库写操作仅限 admin。"""
        user = _make_user("user1")
        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = client.post(
                "/api/v1/partners/souls",
                json={"id": "test-soul", "name": "Test", "content": "# Soul"},
            )
        assert res.status_code == 403

        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = client.put(
                "/api/v1/partners/souls/companion",
                json={"name": "Renamed"},
            )
        assert res.status_code == 403

        with patch("deeptutor.multi_user.context.get_current_user", return_value=user):
            res = client.delete("/api/v1/partners/souls/companion")
        assert res.status_code == 403

    def test_stopped_partner_dict_includes_owner_id(self, client, isolated_root):
        """_stopped_partner_dict 返回中包含 owner_id 字段。"""
        res = _create(client)
        assert res.status_code == 200
        body = res.json()
        assert "owner_id" in body
