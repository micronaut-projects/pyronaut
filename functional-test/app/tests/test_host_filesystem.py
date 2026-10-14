import os
import zoneinfo

# Regression tests for micronaut-projects/pyronaut#333: the GraalPy context runs on the
# virtual filesystem with the Java POSIX backend, whose rename of host files was rejected
# as a non-supported atomic move and whose zoneinfo searched no system time zones.


def test_replace_host_file(tmp_path):
    source = tmp_path / "a"
    target = tmp_path / "b"
    source.write_bytes(b"new")
    target.write_bytes(b"old")

    os.replace(source, target)

    assert not source.exists()
    assert target.read_bytes() == b"new"


def test_rename_host_file_and_directory(tmp_path):
    source = tmp_path / "file"
    source.write_bytes(b"x")
    folder = tmp_path / "folder"
    folder.mkdir()
    (folder / "child").write_bytes(b"y")

    os.rename(source, tmp_path / "renamed")
    os.rename(folder, tmp_path / "moved")

    assert (tmp_path / "renamed").read_bytes() == b"x"
    assert (tmp_path / "moved" / "child").read_bytes() == b"y"


def test_zoneinfo_searches_system_time_zones():
    if os.name == "nt":
        return
    # the default search path, unless the host environment sets PYTHONTZPATH itself
    assert zoneinfo.TZPATH
