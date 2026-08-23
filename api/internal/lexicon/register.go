package lexicon

// Swedish is the only language wired up today. Adding another means
// implementing the two provider interfaces and registering them here.
func init() {
	RegisterDictionary("sv", FolketsProvider{})
	RegisterMorphology("sv", SaldoProvider{})
}
