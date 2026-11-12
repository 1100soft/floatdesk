#!/usr/bin/env python3
"""Floatdesk's KDE/Linux tray: service controls, output status and initial display setup."""
from pathlib import Path
import json,os,subprocess,sys,tempfile
from PySide6.QtCore import QProcess,QTimer,Qt
from PySide6.QtGui import QAction,QIcon
from PySide6.QtWidgets import QApplication,QSystemTrayIcon,QMenu,QWidget,QVBoxLayout,QLabel,QPushButton,QPlainTextEdit,QSpinBox,QMessageBox,QTableWidget,QTableWidgetItem,QStyle

ROOT=Path(os.environ.get('FLOATDESK_DATA_DIR',Path(__file__).resolve().parents[1]))
class Window(QWidget):
    def __init__(self,tray):
        super().__init__();self.tray=tray;self.process=None;self.setWindowTitle('Floatdesk');self.resize(760,520)
        layout=QVBoxLayout(self);self.status=QLabel();layout.addWidget(self.status)
        self.table=QTableWidget(0,4);self.table.setHorizontalHeaderLabels(['Display','Resolution','Position','Refresh Hz']);layout.addWidget(self.table)
        self.count=QSpinBox();self.count.setRange(1,2147483647);self.count.setValue(4);self.count.setPrefix('Outputs to provision: ');layout.addWidget(self.count)
        self.setup=QPushButton('Set up virtual screens');self.setup.clicked.connect(self.prepare);layout.addWidget(self.setup)
        self.auto=QPushButton('Detect / prepare available virtual backend');self.auto.clicked.connect(lambda:self.execute(sys.executable,[str(ROOT/'scripts/auto-virtual-displays.py'),str(self.count.value())]));layout.addWidget(self.auto)
        self.log=QPlainTextEdit();self.log.setReadOnly(True);layout.addWidget(self.log)
        self.timer=QTimer(self);self.timer.timeout.connect(self.refresh);self.timer.start(3000);self.refresh()
    def execute(self,program,args,finished=None):
        if self.process and self.process.state()!=QProcess.NotRunning:return
        p=QProcess(self);self.process=p;p.setProcessChannelMode(QProcess.MergedChannels)
        p.readyReadStandardOutput.connect(lambda:self.log.appendPlainText(bytes(p.readAllStandardOutput()).decode(errors='replace')))
        p.errorOccurred.connect(lambda _:self.log.appendPlainText(p.errorString()))
        p.finished.connect(lambda code,status: (self.log.appendPlainText(f'Finished (exit {code})'),self.refresh(),finished(code) if finished else None))
        p.start(program,args)
    def refresh(self):
        # QProcess keeps slow XRandR/KScreen replies off the Qt event loop.
        if getattr(self,'probe',None) and self.probe.state()!=QProcess.NotRunning:return
        self.probe=QProcess(self);p=self.probe
        def read(code,status):
            if code:return
            try:
                rows=json.loads(bytes(p.readAllStandardOutput()))['outputs'];rows=[r for r in rows if r.get('enabled')]
                self.table.setRowCount(len(rows))
                for i,r in enumerate(rows):
                    mode=next((m for m in r.get('modes',[]) if m.get('id')==r.get('currentModeId')), {})
                    values=[r['name'],f"{r['size']['width']} × {r['size']['height']}",f"{r['pos']['x']}, {r['pos']['y']}",str(mode.get('refreshRate','?'))]
                    for j,value in enumerate(values):self.table.setItem(i,j,QTableWidgetItem(value))
                self.table.resizeColumnsToContents()
            except (KeyError,ValueError):pass
        p.finished.connect(read)
        p.finished.connect(lambda *_:(setattr(self,"probe",None),p.deleteLater()))
        p.start('kscreen-doctor',['-j'])
        self.service=QProcess(self);q=self.service
        q.finished.connect(lambda *_:self.status.setText('Server: '+bytes(q.readAllStandardOutput()).decode().strip()))
        q.finished.connect(lambda *_:q.deleteLater())
        q.start('systemctl',['--user','is-active','floatdesk-live'])
    def shutdown(self):
        self.timer.stop()
        for p in self.findChildren(QProcess):
            p.blockSignals(True)
            if p.state()!=QProcess.NotRunning:p.kill();p.waitForFinished(1000)
    def prepare(self):
        # Existing pools should be reused; the installer independently rejects active topology.
        folder=Path(tempfile.mkdtemp(prefix='floatdesk-setup-'))/'bundle'
        def generated(code):
            if code:return
            self.log.appendPlainText('Installing boot configuration with system authorization. Existing topologies are never replaced. A reboot may be required; it will not happen automatically.')
            def installed(code):
                if not code:self.execute('sh',[str(folder/'install-user.sh')])
            self.execute('pkexec',['sh',str(folder/'install-system.sh')],installed)
        self.execute(sys.executable,[str(ROOT/'scripts/virtual-display-setup.py'),'--write-bundle',str(folder),'--count',str(self.count.value())],generated)

def main():
    app=QApplication(sys.argv);app.setQuitOnLastWindowClosed(False)
    icon=QIcon.fromTheme('video-display')
    if icon.isNull():icon=app.style().standardIcon(QStyle.SP_ComputerIcon)
    tray=QSystemTrayIcon(icon);window=Window(tray);app.aboutToQuit.connect(window.shutdown);menu=QMenu()
    def service(action):
        if action=='start':window.execute('bash',[str(ROOT/'scripts/serve-existing.sh')])
        else:window.execute('systemctl',['--user',action,'floatdesk-live'])
    for label,action in [('Open Floatdesk',lambda:window.show()),('Start server',lambda:service('start')),('Stop server',lambda:service('stop')),('Restart server',lambda:service('restart')),('Virtual screen setup',lambda:window.show()),('Show logs',lambda:window.execute('journalctl',['--user','-u','floatdesk-live','-n','80','--no-pager'])),('Quit tray',app.quit)]:
        item=menu.addAction(label);item.triggered.connect(action)
    tray.setContextMenu(menu);tray.setToolTip('Floatdesk desktop streaming');tray.activated.connect(lambda reason:window.show() if reason==QSystemTrayIcon.Trigger else None);tray.show()
    if '--show' in sys.argv:window.show()
    if '--smoke-test' in sys.argv:QTimer.singleShot(1200,app.quit)
    return app.exec()
if __name__=='__main__':raise SystemExit(main())
