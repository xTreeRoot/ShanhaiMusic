//go:build !darwin

package app

func newMenuBarStatus(menuBarLayout, func(menuBarAction), func()) (menuBarStatus, error) {
	return nil, nil
}
