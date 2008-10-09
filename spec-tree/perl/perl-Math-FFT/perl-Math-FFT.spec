Name:           perl-Math-FFT
Version:        1.28
Release:        1%{?dist}
Summary:        Perl module to calculate Fast Fourier Transforms
# Perl module code is GPL+ or same as Perl itself
# arrays.c has same licence as Perl itself
# FFT code is based on the C routine of fft4g.c Takuya OOURA,
# which is public domain
License:        (GPL+ or Artistic) and Public Domain
Group:          Development/Libraries
URL:            http://search.cpan.org/dist/Math-FFT/
Source0:        http://www.cpan.org/authors/id/R/RK/RKOBES/Math-FFT-%{version}.tar.gz
BuildRoot:      %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)
Requires:       perl(:MODULE_COMPAT_%(eval "`%{__perl} -V:version`"; echo $version))
BuildRequires:  perl(ExtUtils::MakeMaker)

%description
This module implements some algorithms for calculating Fast Fourier
Transforms for one-dimensional data sets of size 2^n. 

%prep
%setup -q -n Math-FFT-%{version}

%build
%{__perl} Makefile.PL INSTALLDIRS=vendor OPTIMIZE="$RPM_OPT_FLAGS"
make %{?_smp_mflags}

%install
rm -rf $RPM_BUILD_ROOT

make pure_install PERL_INSTALL_ROOT=$RPM_BUILD_ROOT

find $RPM_BUILD_ROOT -type f -name .packlist -exec rm -f {} \;
find $RPM_BUILD_ROOT -type f -name '*.bs' -size 0 -exec rm -f {} \;
find $RPM_BUILD_ROOT -depth -type d -exec rmdir {} 2>/dev/null \;

%{_fixperms} $RPM_BUILD_ROOT/*

%check
make test

%clean
rm -rf $RPM_BUILD_ROOT

%files
%defattr(-,root,root,-)
%doc Changes README
%{perl_vendorarch}/auto/*
%{perl_vendorarch}/Math*
%{_mandir}/man3/*

%changelog
* Wed Jun 25 2008 Miroslav Suchy <msuchy@redhat.com> 1.28-1
- Specfile autogenerated by cpanspec 1.77.
