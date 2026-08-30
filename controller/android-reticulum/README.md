# Reticulum sender

Optional. It sends the frozen, selected photo as an LXMF message after the frame has seen the recipient announce.

On the X88, install Termux and Termux:Boot from F-Droid or GitHub. Open Termux once, then:

```sh
pkg update
pkg install python python-cryptography
pip install rns==1.2.0 lxmf==0.9.6
```

Copy this folder to `~/.frame-messenger`, rename the two example files to `config.json` and `reticulum/config`, edit the addresses and recipient name, then run `start-frame-messenger`. Put the same command in `~/.termux/boot/` for cold starts.

Do not copy `sender.identity` between unrelated frames or commit it to Git.


