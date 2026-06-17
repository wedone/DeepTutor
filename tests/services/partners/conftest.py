"""Fixtures for the partners service suite — isolate all paths under tmp_path."""

from __future__ import annotations

from pathlib import Path

import pytest


@pytest.fixture
def partners_root(tmp_path, monkeypatch) -> Path:
    """Redirect the admin workspace (and multi-user roots) under ``tmp_path``.

    Everything the partners layer touches resolves through
    ``deeptutor.multi_user.paths`` — the partners data dir is anchored at the
    admin workspace root and partner scopes are synthetic ``UserScope``s — so
    patching that module is sufficient to keep tests off the real ``data/``.
    """
    from deeptutor.multi_user import paths
    from deeptutor.partners.config.paths import invalidate_owner_cache

    project_root = tmp_path
    admin_root = (project_root / "data").resolve()
    monkeypatch.setattr(paths, "PROJECT_ROOT", project_root)
    monkeypatch.setattr(paths, "ADMIN_WORKSPACE_ROOT", admin_root)
    monkeypatch.setattr(paths, "USERS_ROOT", admin_root / "users")
    monkeypatch.setattr(paths, "SYSTEM_ROOT", admin_root / "system")
    monkeypatch.setattr(paths, "_path_services", {})

    admin_root.mkdir(parents=True, exist_ok=True)
    # 清空 owner 缓存，避免上一个测试的缓存污染当前测试
    invalidate_owner_cache()
    return admin_root / "partners"


@pytest.fixture
def user_partners_root(partners_root):
    """多用户测试辅助 fixture：返回一个工厂函数。

    调用 ``user_partners_root(uid)`` 会创建 ``data/users/<uid>/partners/``
    目录并返回该路径；同时清空 owner 缓存以避免跨测试污染。依赖
    ``partners_root`` 以复用 admin workspace 的路径隔离。
    """
    from deeptutor.multi_user import paths
    from deeptutor.partners.config.paths import invalidate_owner_cache

    def _make(uid: str) -> Path:
        user_partners_dir = paths.USERS_ROOT / uid / "partners"
        user_partners_dir.mkdir(parents=True, exist_ok=True)
        invalidate_owner_cache()
        return user_partners_dir

    return _make
