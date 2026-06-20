"""Path helpers for the Partners data tree (``data/partners/``)."""

from __future__ import annotations

import threading
from pathlib import Path

from deeptutor.partners.helpers import ensure_dir


def _base_dir_for_owner(owner_id: str | None = "") -> Path:
    """按 owner 解析 partner 数据根目录。

    空串或 None → admin workspace（``data/partners/``，与单机模式一致）；
    非空值 → ``data/users/<owner_id>/partners/``。

    复用 ``deeptutor.multi_user.paths`` 的 ADMIN_WORKSPACE_ROOT/USERS_ROOT，
    不经过 get_current_user()，避免 partner 运行时递归（参见 _base_dir 注释）。
    """
    from deeptutor.multi_user.paths import ADMIN_WORKSPACE_ROOT, USERS_ROOT

    owner = (owner_id or "").strip()
    if not owner:
        return ensure_dir(ADMIN_WORKSPACE_ROOT / "partners")
    return ensure_dir(USERS_ROOT / owner / "partners")


def _base_dir() -> Path:
    # Anchored to the admin workspace root (data/partners), NOT the
    # current-user path service: partner runtimes execute inside a synthetic
    # partner scope whose workspace_root lives below this very tree, so
    # resolving through the contextvar here would recurse the layout.
    # Deprecated: 保留以兼容旧调用，新代码应使用 _base_dir_for_owner("")。
    return _base_dir_for_owner("")


def get_data_dir() -> Path:
    return _base_dir()


def get_runtime_subdir(name: str) -> Path:
    """[Deprecated] 使用 get_partner_runtime_subdir 代替。全局共享的运行时子目录。"""
    return ensure_dir(_base_dir() / name)


def get_media_dir(channel: str | None = None) -> Path:
    """[Deprecated] 使用 get_partner_media_dir 代替。全局共享的媒体下载目录。"""
    base = get_runtime_subdir("media")
    return ensure_dir(base / channel) if channel else base


# ── Per-partner path helpers ──────────────────────────────────────


def get_partner_dir(partner_id: str, *, owner_id: str = "") -> Path:
    """data/partners/{partner_id}/ — config, sessions, and workspace."""
    return ensure_dir(_base_dir_for_owner(owner_id) / partner_id)


def get_partner_workspace(partner_id: str, *, owner_id: str = "") -> Path:
    """The partner's scope root (chat user-workspace layout lives below it)."""
    return ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "workspace")


def get_partner_sessions_dir(partner_id: str, *, owner_id: str = "") -> Path:
    return ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "sessions")


def get_partner_media_dir(
    partner_id: str, channel: str | None = None, *, owner_id: str = ""
) -> Path:
    base = ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "media")
    return ensure_dir(base / channel) if channel else base


def get_partner_runtime_subdir(
    partner_id: str, name: str, *, owner_id: str = ""
) -> Path:
    """<partner_dir>/runtime/<name>/ — channel 状态文件目录。"""
    base = ensure_dir(get_partner_dir(partner_id, owner_id=owner_id) / "runtime")
    return ensure_dir(base / name)


# ── Owner 反查机制 ────────────────────────────────────────────────

_owner_cache: dict[str, str] = {}
_owner_cache_lock = threading.Lock()


def _resolve_owner_id(partner_id: str) -> str:
    """通过磁盘扫描查找 partner 的 owner_id。

    先扫 admin 目录，再扫各用户目录；命中即缓存并返回 owner_id；
    未命中返回空串（fallback 到 admin）。带缓存避免反复扫描。
    """
    with _owner_cache_lock:
        if partner_id in _owner_cache:
            return _owner_cache[partner_id]

    from deeptutor.multi_user.paths import ADMIN_WORKSPACE_ROOT, USERS_ROOT

    # 1. admin 目录
    if (ADMIN_WORKSPACE_ROOT / "partners" / partner_id / "config.yaml").exists():
        with _owner_cache_lock:
            _owner_cache[partner_id] = ""
        return ""

    # 2. 各用户目录
    if USERS_ROOT.is_dir():
        for user_entry in USERS_ROOT.iterdir():
            if not user_entry.is_dir():
                continue
            if (user_entry / "partners" / partner_id / "config.yaml").exists():
                owner = user_entry.name
                with _owner_cache_lock:
                    _owner_cache[partner_id] = owner
                return owner

    # 3. 未找到，fallback 到 admin
    with _owner_cache_lock:
        _owner_cache[partner_id] = ""
    return ""


def resolve_owner_for_partner(partner_id: str) -> str:
    """公开入口：反查 partner 的 owner_id。"""
    return _resolve_owner_id(partner_id)


def invalidate_owner_cache(partner_id: str | None = None) -> None:
    """失效缓存（partner 创建/删除后调用）。"""
    with _owner_cache_lock:
        if partner_id is None:
            _owner_cache.clear()
        else:
            _owner_cache.pop(partner_id, None)
