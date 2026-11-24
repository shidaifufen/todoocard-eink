import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ble_pair import pair_device


class PairTests(unittest.IsolatedAsyncioTestCase):
    def factory(self, failures):
        self.calls = []
        calls = self.calls

        class Client:
            is_connected = False

            def __init__(self, device, *, pair, timeout):
                calls.append(("create", pair))
                self.pair = pair

            async def connect(self):
                calls.append(("connect", self.pair))
                if failures:
                    error = failures.pop(0)
                    if error:
                        raise error
                self.is_connected = True

            async def disconnect(self):
                calls.append(("disconnect", self.pair))

            async def read_gatt_char(self, uuid):
                return bytes([75])
        return Client

    async def test_pair_before_connect_and_no_second_pair(self):
        await pair_device(SimpleNamespace(address="FF:FF:99:80:39:01"), print,
                          client_factory=self.factory([]))
        self.assertEqual(self.calls, [("create", True), ("connect", True), ("disconnect", True)])

    async def test_pending_system_pair_is_observed_not_reissued(self):
        states = [False, True]
        async def paired(address):
            return states.pop(0)
        with patch("ble_pair.sys.platform", "win32"):
            await pair_device(SimpleNamespace(address="FF:FF:99:80:39:01"), print,
                client_factory=self.factory([RuntimeError("OPERATION_ALREADY_IN_PROGRESS")]),
                paired_check=paired, poll_seconds=0)
        self.assertEqual([c for c in self.calls if c[0] == "create"], [("create", True), ("create", False)])

    async def test_pending_pair_never_claims_success_on_timeout(self):
        with patch("ble_pair.sys.platform", "win32"):
            with self.assertRaisesRegex(RuntimeError, "未确认配对成功"):
                await pair_device(SimpleNamespace(address="FF:FF:99:80:39:01"), print,
                    client_factory=self.factory([RuntimeError("OPERATION_ALREADY_IN_PROGRESS")]),
                    wait_seconds=0)
        self.assertEqual(len([c for c in self.calls if c[0] == "create"]), 1)

    async def test_unrelated_error_is_not_retried_and_disconnects(self):
        with self.assertRaisesRegex(RuntimeError, "Authentication failed"):
            await pair_device(SimpleNamespace(address="FF:FF:99:80:39:01"), print,
                client_factory=self.factory([RuntimeError("Authentication failed")]))
        self.assertEqual(self.calls[-1], ("disconnect", True))


if __name__ == "__main__":
    unittest.main()
