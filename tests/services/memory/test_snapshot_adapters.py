"""Snapshot adapter tests — focus on the partner-conversation bridge.

Partner runtimes persist their conversations as JSONL under
``<workspace_root>/partners/<id>/sessions/*.jsonl`` (a store separate from the
chat-history SQLite DB). ``read_partner_entities`` bridges those files
into the ``partner`` memory surface so they consolidate into L2/L3 like
any other surface, tagged with the originating partner.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from deeptutor.services.memory.snapshot import adapters


class _FakePathService:
    def __init__(self, root: Path) -> None:
        self.workspace_root = root


def _write_session(sessions_dir: Path, key: str, turns: list[tuple[str, str]]) -> None:
    sessions_dir.mkdir(parents=True, exist_ok=True)
    with (sessions_dir / f"{key}.jsonl").open("w", encoding="utf-8") as fh:
        for i, (role, content) in enumerate(turns):
            fh.write(
                json.dumps(
                    {"role": role, "content": content, "timestamp": f"2026-06-16T10:0{i}:00"},
                    ensure_ascii=False,
                )
                + "\n"
            )


@pytest.fixture
def partner_tree(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Make ``tmp_path`` the workspace root and route path service there."""
    monkeypatch.setattr(adapters, "get_path_service", lambda: _FakePathService(tmp_path))
    return tmp_path


def test_partner_sessions_become_tagged_entities(partner_tree: Path) -> None:
    pdir = partner_tree / "partners" / "bot1"
    (pdir).mkdir(parents=True)
    (pdir / "config.yaml").write_text("name: Math Tutor\n", encoding="utf-8")
    _write_session(
        pdir / "sessions",
        "telegram:42",
        [("user", "what is a limit"), ("assistant", "a limit is...")],
    )

    entities = adapters.read_partner_entities()

    assert len(entities) == 1
    ent = entities[0]
    assert ent.id == "bot1:telegram:42"
    # Partner tag lands in both the label and the metadata.
    assert "Math Tutor" in ent.label
    assert ent.metadata["partner_id"] == "bot1"
    assert ent.metadata["partner_name"] == "Math Tutor"
    assert ent.metadata["message_count"] == 2
    assert ent.metadata["archived"] is False
    # Conversation is inlined as role blocks for L2 to chew on.
    assert "### user" in ent.content
    assert "what is a limit" in ent.content


def test_archived_sessions_included_and_flagged(partner_tree: Path) -> None:
    pdir = partner_tree / "partners" / "bot1"
    pdir.mkdir(parents=True)
    _write_session(pdir / "sessions", "web:s1", [("user", "hi"), ("assistant", "hello")])
    _write_session(
        pdir / "sessions",
        "_archived_20260101-000000_web_s1",
        [("user", "old"), ("assistant", "older")],
    )

    entities = adapters.read_partner_entities()

    by_id = {e.id: e for e in entities}
    assert len(by_id) == 2
    archived = next(e for e in entities if e.metadata["archived"])
    assert archived.metadata["session_key"].startswith("_archived_")


def test_empty_sessions_skipped_and_name_falls_back_to_id(partner_tree: Path) -> None:
    pdir = partner_tree / "partners" / "bot2"
    pdir.mkdir(parents=True)
    # whitespace-only content → no usable turns → no entity
    _write_session(pdir / "sessions", "web:empty", [("user", "   "), ("assistant", "")])

    entities = adapters.read_partner_entities()
    assert entities == []


def test_missing_config_uses_dir_id_as_name(partner_tree: Path) -> None:
    pdir = partner_tree / "partners" / "bot3"
    pdir.mkdir(parents=True)
    _write_session(pdir / "sessions", "web:s", [("user", "q"), ("assistant", "a")])

    entities = adapters.read_partner_entities()
    assert len(entities) == 1
    assert entities[0].metadata["partner_name"] == "bot3"


def test_non_admin_scope_isolated_from_admin_partners(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A regular user sees only their own partners, never the admin's."""
    admin_root = tmp_path / "admin"
    user_root = tmp_path / "users" / "u1"

    # admin 的 partner
    admin_pdir = admin_root / "partners" / "bot_admin"
    admin_pdir.mkdir(parents=True)
    _write_session(admin_pdir / "sessions", "web:s",
                   [("user", "q"), ("assistant", "a")])

    # 用户自己的 partner
    user_pdir = user_root / "partners" / "bot_user"
    user_pdir.mkdir(parents=True)
    (user_pdir / "config.yaml").write_text("name: User Bot\n", encoding="utf-8")
    _write_session(user_pdir / "sessions", "web:s",
                   [("user", "hi"), ("assistant", "yo")])

    monkeypatch.setattr(adapters, "get_path_service",
                        lambda: _FakePathService(user_root))

    entities = adapters.read_partner_entities()
    assert len(entities) == 1
    assert entities[0].metadata["partner_id"] == "bot_user"


def test_fingerprint_changes_when_conversation_grows(partner_tree: Path) -> None:
    pdir = partner_tree / "partners" / "bot1"
    pdir.mkdir(parents=True)
    _write_session(pdir / "sessions", "web:s", [("user", "q1"), ("assistant", "a1")])
    fp1 = adapters.read_partner_entities()[0].fingerprint

    # Append another exchange → fingerprint must move so refresh detects it.
    _write_session(
        pdir / "sessions",
        "web:s",
        [("user", "q1"), ("assistant", "a1"), ("user", "q2"), ("assistant", "a2")],
    )
    fp2 = adapters.read_partner_entities()[0].fingerprint
    assert fp1 != fp2
