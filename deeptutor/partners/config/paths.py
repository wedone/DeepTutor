"""Path helpers for the Partners data tree (``data/partners/``)."""

from __future__ import annotations

import warnings
from pathlib import Path

from deeptutor.partners.helpers import ensure_dir

# ── 缓存：partner_id → owner_id（空串表示 admin 目录，None 表示未找到）──
_owner_cache: dict[str, str | None] = {}


def _base_dir_for_owner(owner_id: str | None) -> Path:
    """根据 owner_id 返回 partners 基础目录。

    - owner_id 为空串或 None → data/partners/（admin 目录）
    - owner_id 为非空值 → data/users/<owner_id>/partners/
    """
    from deeptutor.multi_user.paths import USERS_ROOT, get_admin_path_service

    root = get_admin_path_service().workspace_root
    if not owner_id:
        return ensure_dir(root / "partners")
    return ensure_dir(USERS_ROOT / owner_id / "partners")


def _base_dir() -> Path:
    # Anchored to the admin workspace root (data/partners), NOT the
    # current-user path service: partner runtimes execute inside a synthetic
    # partner scope whose workspace_root lives below this very tree, so
    # resolving through the contextvar here would recurse the layout.
    return _base_dir_for_owner("")


def get_data_dir() -> Path:
    return _base_dir()


def get_runtime_subdir(name: str) -> Path:
    warnings.warn(
        "get_runtime_subdir 已弃用，请使用 get_partner_runtime_subdir",
        DeprecationWarning,
        stacklevel=2,
    )
    return ensure_dir(_base_dir() / name)


def get_media_dir(channel: str | None = None) -> Path:
    """Shared media download dir used by channel implementations."""
    warnings.warn(
        "get_media_dir 已弃用，请使用 get_partner_media_dir",
        DeprecationWarning,
        stacklevel=2,
    )
    base = ensure_dir(_base_dir() / "media")
    return ensure_dir(base / channel) if channel else base


# ── Owner 反查 ────────────────────────────────────────────────────


def _resolve_owner_id(partner_id: str) -> str | None:
    """反查 partner_id 所属的 owner_id。

    - 返回空串 "" 表示位于 admin 目录 data/partners/<partner_id>/
    - 返回 uid 表示位于 data/users/<uid>/partners/<partner_id>/
    - 返回 None 表示未找到
    """
    if partner_id in _owner_cache:
        return _owner_cache[partner_id]

    from deeptutor.multi_user.paths import USERS_ROOT, get_admin_path_service

    root = get_admin_path_service().workspace_root

    # 1. 扫描 admin 目录
    if (root / "partners" / partner_id / "config.yaml").exists():
        _owner_cache[partner_id] = ""
        return ""

    # 2. 扫描各用户目录
    users_root = USERS_ROOT
    if users_root.is_dir():
        for uid_entry in users_root.iterdir():
            if uid_entry.is_dir() and (
                uid_entry / "partners" / partner_id / "config.yaml"
            ).exists():
                _owner_cache[partner_id] = uid_entry.name
                return uid_entry.name

    _owner_cache[partner_id] = None
    return None


def invalidate_owner_cache() -> None:
    """清空 owner 反查缓存。"""
    _owner_cache.clear()


# ── Per-partner path helpers ──────────────────────────────────────


def get_partner_dir(partner_id: str, *, owner_id: str | None = None) -> Path:
    """data/partners/{partner_id}/ — config, sessions, and workspace."""
    return ensure_dir(_base_dir_for_owner(owner_id) / partner_id)


def get_partner_workspace(partner_id: str, *, owner_id: str | None = None) -> Path:
    """The partner's scope root (chat user-workspace layout lives below it)."""
    return ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "workspace")


def get_partner_sessions_dir(
    partner_id: str, *, owner_id: str | None = None
) -> Path:
    return ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "sessions")


def get_partner_media_dir(
    partner_id: str, channel: str | None = None, *, owner_id: str | None = None
) -> Path:
    base = ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "media")
    return ensure_dir(base / channel) if channel else base


def get_partner_runtime_subdir(
    partner_id: str, channel: str, *, owner_id: str | None = None
) -> Path:
    """返回 <partner_dir>/runtime/<channel>/ 目录。"""
    base = ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "runtime")
    return ensure_dir(base / channel)
