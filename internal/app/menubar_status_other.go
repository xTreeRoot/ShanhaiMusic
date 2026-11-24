//go:build !darwin

package app

func newMenuBarStatus(func(menuBarAction), func()) (menuBarStatus, error) { return nil, nil }
