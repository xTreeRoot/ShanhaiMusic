//go:build !darwin

package app

func (a *app) trayTransportAction() menuBarAction { return menuBarOpenMenu }
