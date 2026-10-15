// Moth adapter: GPL-3.0-or-later. Xray remains MPL-2.0.
package libbox

import (
    "bytes"
    "fmt"
    "os"
    "sync"
    "syscall"

    xcore "github.com/xtls/xray-core/core"
    _ "github.com/xtls/xray-core/main/distro/all"
    "github.com/xtls/xray-core/transport/internet"
)

var mothXrayLock sync.RWMutex
var mothXrayPlatform PlatformInterface
var mothXrayOnce sync.Once
var mothXrayInitError error

type MothXrayService struct { instance *xcore.Instance }

func MothStartXray(config string, platform PlatformInterface) (*MothXrayService, error) {
    mothXrayLock.Lock()
    mothXrayPlatform = platform
    mothXrayLock.Unlock()
    mothXrayOnce.Do(func() {
        controller := func(network, address string, conn syscall.RawConn) error {
            var protectErr error
            err := conn.Control(func(fd uintptr) {
                mothXrayLock.RLock()
                defer mothXrayLock.RUnlock()
                if mothXrayPlatform == nil { protectErr = fmt.Errorf("VPN platform unavailable"); return }
                protectErr = mothXrayPlatform.AutoDetectInterfaceControl(int32(fd))
            })
            if err != nil { return err }
            return protectErr
        }
        mothXrayInitError = internet.RegisterDialerController(controller)
        if mothXrayInitError == nil { mothXrayInitError = internet.RegisterListenerController(controller) }
    })
    if mothXrayInitError != nil { return nil, mothXrayInitError }
    parsed, err := xcore.LoadConfig("json", bytes.NewReader([]byte(config)))
    if err != nil { return nil, err }
    instance, err := xcore.New(parsed)
    if err != nil { return nil, err }
    if err = instance.Start(); err != nil { instance.Close(); return nil, err }
    return &MothXrayService{instance: instance}, nil
}

// MothSetTrustedCertFile points Go's TLS roots (used by Xray) at a PEM bundle. Setting SSL_CERT_FILE from Java does not
// reach Go: the Go runtime copies the environment once at load time from the process's original environ.
// Must be called before the first TLS handshake, because Go loads the system roots only once per process.
func MothSetTrustedCertFile(path string) error { return os.Setenv("SSL_CERT_FILE", path) }

func (s *MothXrayService) Close() error { return s.instance.Close() }
