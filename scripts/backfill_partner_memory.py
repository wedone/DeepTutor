#!/usr/bin/env python3
"""回填 Partner 历史会话到 Memory L1 Trace。

修复版本部署后，新的 Partner 会话会自动写入 L1 trace。但之前的历史会话
不会自动回填。此脚本扫描所有 partner 的 sessions/*.jsonl 文件，将每个
turn 写入 L1 trace（surface="partner", kind="turn"）。

用法:
    python scripts/backfill_partner_memory.py [--dry-run] [--partner-id <id>]

参数:
    --dry-run        只打印将要写入的内容，不实际写入
    --partner-id     只回填指定 partner 的会话（默认回填所有）
    --owner-id       多用户模式下指定用户 ID（默认 admin）
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
from pathlib import Path
from typing import Any

# PyPI 部署的包已在 Python 路径中，无需手动添加 sys.path
from deeptutor.multi_user.models import CurrentUser, UserScope
from deeptutor.multi_user.paths import get_admin_path_service, user_context
from deeptutor.partners.config.paths import _admin_base_dir, _base_dir_for_owner
from deeptutor.services.memory.store import MemoryStore
from deeptutor.services.memory.trace import TraceEvent

logger = logging.getLogger(__name__)


def parse_jsonl_session(session_file: Path) -> list[dict[str, Any]]:
    """解析 JSONL session 文件，返回消息列表。"""
    records: list[dict[str, Any]] = []
    try:
        with session_file.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(data, dict) and data.get("role") and data.get("content"):
                    records.append(data)
    except OSError as e:
        logger.warning("无法读取 session 文件 %s: %s", session_file, e)
        return []
    return records


def find_partner_sessions(partner_id: str | None = None, owner_id: str | None = None) -> list[tuple[str, Path]]:
    """查找所有 partner 的 session 文件。

    Returns:
        [(partner_id, session_file_path), ...]
    """
    results: list[tuple[str, Path]] = []

    # admin 目录
    admin_dir = _admin_base_dir()
    if admin_dir.exists():
        for entry in admin_dir.iterdir():
            if not entry.is_dir():
                continue
            if partner_id and entry.name != partner_id:
                continue
            if not (entry / "config.yaml").exists():
                continue
            sessions_dir = entry / "sessions"
            if not sessions_dir.is_dir():
                continue
            for session_file in sorted(sessions_dir.glob("*.jsonl")):
                if session_file.stem.startswith("_archived_"):
                    continue
                results.append((entry.name, session_file))

    # 用户目录
    if owner_id:
        user_partners = _base_dir_for_owner(owner_id)
        if user_partners.exists():
            for entry in user_partners.iterdir():
                if not entry.is_dir():
                    continue
                if partner_id and entry.name != partner_id:
                    continue
                if not (entry / "config.yaml").exists():
                    continue
                sessions_dir = entry / "sessions"
                if not sessions_dir.is_dir():
                    continue
                for session_file in sorted(sessions_dir.glob("*.jsonl")):
                    if session_file.stem.startswith("_archived_"):
                        continue
                    results.append((entry.name, session_file))

    return results


def backfill_partner_session(
    partner_id: str,
    session_file: Path,
    *,
    dry_run: bool = False,
) -> int:
    """回填单个 partner session 到 L1 trace。

    Returns:
        写入的 turn 数量
    """
    session_key = session_file.stem
    records = parse_jsonl_session(session_file)

    if not records:
        logger.info("跳过空 session: %s/%s", partner_id, session_key)
        return 0

    # 将消息配对为 turn（user + assistant）
    turns: list[dict[str, Any]] = []
    current_user_msg: dict[str, Any] | None = None

    for record in records:
        role = record.get("role", "")
        content = str(record.get("content", "")).strip()
        timestamp = record.get("timestamp", "")

        if role == "user" and content:
            current_user_msg = {
                "content": content,
                "timestamp": timestamp,
                "channel": record.get("channel", ""),
                "sender_id": record.get("sender_id", ""),
            }
        elif role == "assistant" and content and current_user_msg:
            turns.append({
                "user_message": current_user_msg["content"],
                "user_timestamp": current_user_msg["timestamp"],
                "assistant_message": content,
                "assistant_timestamp": timestamp,
                "channel": current_user_msg.get("channel", record.get("channel", "")),
                "sender_id": current_user_msg.get("sender_id", ""),
            })
            current_user_msg = None

    if not turns:
        logger.info("跳过无 turn 的 session: %s/%s", partner_id, session_key)
        return 0

    logger.info(
        "处理 session %s/%s: %d 条消息, %d 个 turn",
        partner_id, session_key, len(records), len(turns)
    )

    if dry_run:
        for i, turn in enumerate(turns, 1):
            user_preview = turn["user_message"][:50].replace("\n", " ")
            assistant_preview = turn["assistant_message"][:50].replace("\n", " ")
            print(f"  [DRY-RUN] Turn {i}:")
            print(f"    User: {user_preview}...")
            print(f"    Assistant: {assistant_preview}...")
        return len(turns)

    # 实际写入 L1 trace（使用 asyncio 运行 async emit）
    written = 0
    store = MemoryStore()

    async def emit_turns():
        nonlocal written
        for i, turn in enumerate(turns, 1):
            turn_id = f"partner-{partner_id}-{session_key}-{i:04d}"
            payload: dict[str, Any] = {
                "action": "turn",
                "partner_id": partner_id,
                "session_key": session_key,
                "channel": turn.get("channel", ""),
                "sender_id": turn.get("sender_id", ""),
                "user_message": turn["user_message"],
                "assistant_message": turn["assistant_message"],
                "backfilled": True,  # 标记为回填数据
            }

            # 使用 admin scope 写入全局 memory 目录（与其他 surface 的 trace 在同一位置）
            admin_scope = UserScope(
                kind="admin",
                user_id="local-admin",
                root=get_admin_path_service().workspace_root,
            )
            admin_user = CurrentUser(
                id="local-admin",
                username="local",
                role="admin",
                scope=admin_scope,
            )
            with user_context(admin_user):
                evt = TraceEvent.new(
                    surface="partner",
                    kind="turn",
                    payload=payload,
                    session_id=session_key,
                    turn_id=turn_id,
                )
                await store.emit(evt)
                written += 1

    asyncio.run(emit_turns())

    logger.info("已写入 %d 个 turn 到 L1 trace", written)
    return written


def main() -> None:
    parser = argparse.ArgumentParser(
        description="回填 Partner 历史会话到 Memory L1 Trace"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印将要写入的内容，不实际写入"
    )
    parser.add_argument(
        "--partner-id",
        type=str,
        help="只回填指定 partner 的会话（默认回填所有）"
    )
    parser.add_argument(
        "--owner-id",
        type=str,
        help="多用户模式下指定用户 ID（默认 admin）"
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="详细输出"
    )
    args = parser.parse_args()

    # 配置日志
    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    # 查找所有 partner session
    sessions = find_partner_sessions(
        partner_id=args.partner_id,
        owner_id=args.owner_id
    )

    if not sessions:
        logger.info("未找到任何 partner session 文件")
        return

    logger.info("找到 %d 个 session 文件", len(sessions))

    if args.dry_run:
        print("\n=== DRY-RUN 模式 ===\n")

    # 回填每个 session
    total_turns = 0
    for partner_id, session_file in sessions:
        turns = backfill_partner_session(
            partner_id,
            session_file,
            dry_run=args.dry_run
        )
        total_turns += turns

    # 汇总
    mode = "[DRY-RUN] " if args.dry_run else ""
    logger.info(
        "%s回填完成: %d 个 session, %d 个 turn",
        mode, len(sessions), total_turns
    )

    if args.dry_run:
        print("\n=== 以上为 DRY-RUN 预览，实际运行请去掉 --dry-run 参数 ===\n")


if __name__ == "__main__":
    main()
