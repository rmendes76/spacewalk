--
-- Copyright (c) 2008 Red Hat, Inc.
--
-- This software is licensed to you under the GNU General Public License,
-- version 2 (GPLv2). There is NO WARRANTY for this software, express or
-- implied, including the implied warranties of MERCHANTABILITY or FITNESS
-- FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
-- along with this software; if not, see
-- http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
-- 
-- Red Hat trademarks are not licensed under GPLv2. No permission is
-- granted to use or replicate Red Hat trademarks that are incorporated
-- in this software or its documentation. 
--
--
--
--
-- Preferences for an org

create table
rhnOrgInfo
(
	org_id			number
				constraint rhn_orginfo_oid_nn not null
				constraint rhn_orginfo_oid_fk
					references web_customer(id),
	default_group_type	number default(2)
				constraint rhn_orginfo_dgt_nn not null
				constraint rhn_orginfo_dgt_fk
					references rhnServerGroupType(id),
	created			date default(sysdate)
				constraint rhn_orginfo_created_nn not null,
	modified		date default(sysdate)
				constraint rhn_orginfo_modified_nn not null
)
	storage ( freelists 16 )
	enable row movement
	initrans 32;

create unique index rhn_orginfo_oid_uq
	on rhnOrgInfo(org_id)
	tablespace [[2m_tbs]]
	storage ( freelists 16 )
	initrans 32;

create or replace trigger
rhn_orginfo_mod_trig
before insert or update on rhnOrgInfo
for each row
begin
	:new.modified := sysdate;
end;
/
show errors

--
-- Revision 1.2  2003/01/30 16:11:28  pjones
-- storage parameters, also fix deps to make it build again
--
-- Revision 1.1  2002/05/31 19:47:12  pjones
-- org info for chip.  still could use some discussion.
--
