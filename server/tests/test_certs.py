from zoomcapture_server import certs


def test_pin_is_stable_when_reissued(tmp_path):
    cert, key = tmp_path / "s.crt", tmp_path / "s.key"
    certs.create_self_signed(cert, key, ["10.1.2.3"])
    pin = certs.public_key_pin(cert)
    assert certs.cert_hosts(cert) == ["10.1.2.3"]

    # IP アドレスが変わって作り直しても、鍵を使い回すのでフィンガープリントは変わらない
    certs.create_self_signed(cert, key, ["10.9.9.9", "lab.example.ac.jp"])
    assert certs.public_key_pin(cert) == pin
    assert certs.cert_hosts(cert) == ["10.9.9.9", "lab.example.ac.jp"]

    certs.create_self_signed(cert, key, ["10.9.9.9"], reuse_key=False)
    assert certs.public_key_pin(cert) != pin
