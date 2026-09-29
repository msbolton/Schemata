class Schemata < Formula
  desc "Schema-of-schemas compiler: one model, Protobuf, Postgres DDL, XML Schema out"
  homepage "https://github.com/msbolton/Schemata"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/msbolton/Schemata/releases/download/v0.3.0/schemata-0.3.0-macos-arm64.tar.gz"
      sha256 "ad113df9a49221166326f76e7206fac1eb5dcd6d8db3a22a75821ad3b5b4000a"
    end
    on_intel do
      url "https://github.com/msbolton/Schemata/releases/download/v0.3.0/schemata-0.3.0-macos-x64.tar.gz"
      sha256 "98a86782d988fcd3b4c15adedd9ca51278eeef160bd59900b4ced29af6b37f3c"
    end
  end

  on_linux do
    depends_on arch: :x86_64
    on_intel do
      url "https://github.com/msbolton/Schemata/releases/download/v0.3.0/schemata-0.3.0-linux-x64.tar.gz"
      sha256 "e764014c76a24102c38675b4de44d57fc922b371b442e53fcb83d590adc52a99"
    end
  end

  def install
    bin.install "schemata"
  end

  test do
    (testpath/"t.schemata").write <<~EOS
      namespace t

      enum Kind { #1 personal, #2 work }

      record Contact { @sql(key) #1 id: int64  #2 kind: Kind = personal }
    EOS
    assert_match "schemata #{version}", shell_output("#{bin}/schemata --version")
    # A default is lossy for Protobuf, so `check` reports warnings and exits 2.
    shell_output("#{bin}/schemata check #{testpath}/t.schemata", 2)
  end
end
