"""Production app source for platform-substituted JVM checks after modularization."""
from pathlib import Path
ROOT=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
def marker(source,text,start=0):
    """Index of a splice marker, failing with the check, the marker and the remedy instead of a bare ValueError."""
    at=source.find(text,start)
    if at<0:
        import sys
        raise SystemExit(f"{Path(sys.argv[0]).name}: splice marker {text!r} is missing from production source. "
                         "A declaration was moved or renamed; point the check at its new location.")
    return at
def application_sources():
    return '\n'.join((ROOT/name).read_text() for name in ('MainActivity.kt','WorkspaceRuntime.kt','DesktopScene.kt','WorkspaceInteraction.kt'))
def native_journal():
    """The platform-free journal, compiled whole into any check whose production code records native operations."""
    return (ROOT/'NativeJournal.kt').read_text().split('\n',1)[1]+'\n'
